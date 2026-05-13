package es.ual.node.custodyliveness.application;

import es.ual.node.custodyliveness.domain.OriginCustodianHealth;
import es.ual.node.custodyliveness.ports.out.OriginCustodianHealthPort;
import es.ual.node.filesystem.domain.FragmentHealthStatus;
import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.ports.out.FragmentPlacementPort;
import es.ual.node.fragmentstorage.domain.CustodyInventoryItem;
import es.ual.node.fragmentstorage.ports.out.RemoteCustodyInventoryClientPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Origen-side servicio del probe inverso.
 *
 * <p>Detecta custodians silentes (no han iniciado probe en {@code baseInterval +
 * inverseProbeTolerance}) y dispara probe inverso preguntando al custodian su inventario actual.
 *
 * <ul>
 *   <li><b>Custodian responde</b>: marcar {@link FragmentHealthStatus#OK} los fragments presentes
 *       en su inventario; marcar {@link FragmentHealthStatus#PERDIDO} los que el origen esperaba y
 *       no aparecen (warn grave).
 *   <li><b>Custodian NO responde (1ª vez)</b>: marcar todos sus placements OK como {@link
 *       FragmentHealthStatus#EN_RIESGO}, incrementar {@code consecutive_failures}.
 *   <li><b>Custodian NO responde tras N intentos</b> ({@code unresponsive-threshold-attempts}):
 *       transición EN_RIESGO → {@link FragmentHealthStatus#PERDIDO}. Aunque vuelva online, fragment
 *       permanece PERDIDO.
 * </ul>
 *
 * <p>El servicio NO purga fragments, solo actualiza estado. La política de re-upload total cuando
 * el umbral de riesgo se cruza vive en el {@code FileIntegrityRiskOrchestrator}.
 */
public class OriginInverseProbeService {

  private static final Logger LOGGER = LoggerFactory.getLogger(OriginInverseProbeService.class);

  private final FragmentPlacementPort placementPort;
  private final OriginCustodianHealthPort custodianHealthPort;
  private final RemoteCustodyInventoryClientPort inventoryClient;
  private final CustodyLivenessProperties livenessProperties;
  private final String selfNodeId;
  private final Clock clock;

  /** Creates service. */
  public OriginInverseProbeService(
      final FragmentPlacementPort placementPort,
      final OriginCustodianHealthPort custodianHealthPort,
      final RemoteCustodyInventoryClientPort inventoryClient,
      final CustodyLivenessProperties livenessProperties,
      final String selfNodeId,
      final Clock clock) {
    if (placementPort == null
        || custodianHealthPort == null
        || inventoryClient == null
        || livenessProperties == null
        || selfNodeId == null
        || selfNodeId.isBlank()
        || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null/blank");
    }
    this.placementPort = placementPort;
    this.custodianHealthPort = custodianHealthPort;
    this.inventoryClient = inventoryClient;
    this.livenessProperties = livenessProperties;
    this.selfNodeId = selfNodeId.trim();
    this.clock = clock;
  }

  /**
   * Ejecuta un ciclo completo: enumera custodians silentes y dispara probes inversos.
   *
   * @return resumen del ciclo
   */
  public CycleSummary runOnce() {
    final Instant now = Instant.now(clock);
    final Duration tolerance =
        Duration.ofSeconds(
            livenessProperties.getBaseIntervalSeconds()
                + livenessProperties.getInverseProbeToleranceSeconds());
    final Instant threshold = now.minus(tolerance);
    final List<OriginCustodianHealth> silent = custodianHealthPort.findSilentCustodians(threshold);
    int probedCustodians = 0;
    int markedOk = 0;
    int markedAtRisk = 0;
    int markedLost = 0;

    for (OriginCustodianHealth record : silent) {
      probedCustodians++;
      // Lookup primario por cryptographic custodian id. Para placements del flujo
      // (upload directo cliente) el campo custodian_node_id se persiste como sentinel legacy
      // "peer@<baseUrl>" en lugar del id cryptographic, así que el primer query devuelve
      // vacío y necesitamos el fallback por baseUrl. Sin este fallback, post-RETURN_TO_TUTOR
      // el inverse probe queda mudo: encuentra al custodian silent en origin_custodian_health
      // (registrado con cryptographic id) pero no localiza placements (sentinel baseUrl) y
      // saltea sin disparar PERDIDO ni warns. Mismo fallback que aplica
      // OriginInboundKeepListService.
      List<FragmentPlacement> placementsRaw =
          placementPort.findByCustodianNodeId(record.custodianNodeId());
      if (placementsRaw.isEmpty()
          && record.custodianBaseUrl() != null
          && !record.custodianBaseUrl().isBlank()) {
        placementsRaw = placementPort.findByCustodianBaseUrl(record.custodianBaseUrl());
      }
      final List<FragmentPlacement> expected =
          placementsRaw.stream()
              .filter(p -> p.healthStatus() != FragmentHealthStatus.PERDIDO)
              .toList();
      if (expected.isEmpty()) {
        continue;
      }
      final List<CustodyInventoryItem> inventory;
      try {
        inventory = inventoryClient.fetchInventory(record.custodianBaseUrl(), selfNodeId);
      } catch (RuntimeException ex) {
        // Network error → tratamos como "no respondió" (la implementación canónica
        // SignedHttpRemoteCustodyInventoryClient devuelve lista vacía en errors, pero defensivo
        // capturamos también las RuntimeException por si otro adapter lanza).
        markedAtRisk += handleNoResponse(record, expected, now);
        markedLost += handleUnresponsiveThresholdCrossed(record, expected, now);
        continue;
      }
      if (inventory.isEmpty()) {
        // SignedHttp adapter devuelve empty en errors. Ambiguo entre "custodian responde con
        // 0 fragments" y "no respondió". Tratamos ambos como no-respondió (más conservador).
        markedAtRisk += handleNoResponse(record, expected, now);
        markedLost += handleUnresponsiveThresholdCrossed(record, expected, now);
        continue;
      }
      // Custodian responde con inventario.
      final Set<String> heldFragmentIds = new HashSet<>();
      for (CustodyInventoryItem item : inventory) {
        heldFragmentIds.add(item.fragmentId());
      }
      for (FragmentPlacement p : expected) {
        if (heldFragmentIds.contains(p.fragmentId())) {
          placementPort.updateHealth(p.withHealth(FragmentHealthStatus.OK, now));
          markedOk++;
        } else {
          // Caso warn grave: custodian responde pero declara NO tener un fragment que el origen
          // esperaba.
          placementPort.updateHealth(p.withHealth(FragmentHealthStatus.PERDIDO, now));
          markedLost++;
          LOGGER
              .atWarn()
              .setMessage(
                  "Origin inverse probe: custodian inventory does NOT contain expected fragment —"
                      + " marking PERDIDO")
              .addKeyValue("event", "FRAGMENT_NOT_FOUND_AT_CUSTODIAN")
              .addKeyValue("severity", "high")
              .addKeyValue("custodianNodeId", record.custodianNodeId())
              .addKeyValue("fragmentId", p.fragmentId())
              .log();
        }
      }
      // Reset health record: custodian respondió OK.
      custodianHealthPort.upsertOnInboundProbe(
          record.custodianNodeId(), record.custodianBaseUrl(), now);
    }

    if (probedCustodians > 0) {
      LOGGER
          .atInfo()
          .setMessage("Origin inverse probe cycle complete")
          .addKeyValue("event", "CUSTODY_LIVENESS_INVERSE_CYCLE")
          .addKeyValue("probedCustodians", probedCustodians)
          .addKeyValue("markedOk", markedOk)
          .addKeyValue("markedAtRisk", markedAtRisk)
          .addKeyValue("markedLost", markedLost)
          .log();
    }
    return new CycleSummary(probedCustodians, markedOk, markedAtRisk, markedLost);
  }

  /**
   * Custodian no responde: incrementa consecutive_failures + marca placements EN_RIESGO. Devuelve
   * cuántos placements transicionaron a EN_RIESGO en este intento (no incluye los que ya estaban
   * EN_RIESGO).
   */
  private int handleNoResponse(
      final OriginCustodianHealth record,
      final List<FragmentPlacement> expected,
      final Instant now) {
    final int updatedFailures = record.consecutiveFailures() + 1;
    custodianHealthPort.save(
        new OriginCustodianHealth(
            record.custodianNodeId(),
            record.custodianBaseUrl(),
            record.lastInboundProbeAt(),
            updatedFailures,
            now));
    int newAtRisk = 0;
    for (FragmentPlacement p : expected) {
      if (p.healthStatus() == FragmentHealthStatus.OK) {
        placementPort.updateHealth(p.withHealth(FragmentHealthStatus.EN_RIESGO, now));
        newAtRisk++;
      }
    }
    LOGGER
        .atWarn()
        .setMessage(
            "Origin inverse probe: custodian silent, marking placements EN_RIESGO (consecutive"
                + " failure)")
        .addKeyValue("event", "CUSTODIAN_SILENT")
        .addKeyValue("severity", "medium")
        .addKeyValue("custodianNodeId", record.custodianNodeId())
        .addKeyValue("consecutiveFailures", updatedFailures)
        .addKeyValue("newAtRiskPlacements", newAtRisk)
        .log();
    return newAtRisk;
  }

  /**
   * Si {@code consecutive_failures} cruza el umbral configurable, transición EN_RIESGO → PERDIDO
   * (irrevocable). Devuelve cuántos pasaron a PERDIDO en este intento.
   */
  private int handleUnresponsiveThresholdCrossed(
      final OriginCustodianHealth record,
      final List<FragmentPlacement> expected,
      final Instant now) {
    final int threshold = livenessProperties.getUnresponsiveThresholdAttempts();
    final int currentFailures = record.consecutiveFailures() + 1;
    if (currentFailures < threshold) {
      return 0;
    }
    int newLost = 0;
    for (FragmentPlacement p : expected) {
      if (p.healthStatus() == FragmentHealthStatus.EN_RIESGO
          || (p.healthStatus() == FragmentHealthStatus.OK && currentFailures == 1)) {
        // Casos: ya en EN_RIESGO, o caso degenerado threshold=1 que salta directo de OK a PERDIDO.
        placementPort.updateHealth(p.withHealth(FragmentHealthStatus.PERDIDO, now));
        newLost++;
      }
    }
    if (newLost > 0) {
      LOGGER
          .atWarn()
          .setMessage(
              "Origin inverse probe: custodian unresponsive threshold crossed, placements PERDIDO"
                  + " (irrevocable)")
          .addKeyValue("event", "CUSTODIAN_PRESUMED_DEAD")
          .addKeyValue("severity", "critical")
          .addKeyValue("custodianNodeId", record.custodianNodeId())
          .addKeyValue("consecutiveFailures", currentFailures)
          .addKeyValue("threshold", threshold)
          .addKeyValue("newLostPlacements", newLost)
          .log();
    }
    return newLost;
  }

  /** Resumen de un ciclo del probe inverso. */
  public record CycleSummary(
      int probedCustodians, int markedOk, int markedAtRisk, int markedLost) {}
}
