package es.ual.node.custodyliveness.application;

import es.ual.node.custodyliveness.ports.out.OriginCustodianHealthPort;
import es.ual.node.filesystem.domain.FragmentHealthStatus;
import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.ports.out.FragmentPlacementPort;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Origin-side handler del probe iniciado por el custodian.
 *
 * <p>El custodian envía la lista de fragments que tiene custodiados para el {@code requesterNodeId}
 * (X-Node-Id en el header firmado, que debe matchear este nodo). El servicio:
 *
 * <ol>
 *   <li>Carga los placements activos (no PERDIDOS) del custodian en local.
 *   <li>Para cada fragmento listado por el custodian que esté en placements: marca {@code
 *       health_status=OK, last_check_at=now, consecutive_failures=0}.
 *   <li>Devuelve la whitelist de fragments que el origen quiere conservar, los listados por el
 *       custodian que aparecen en placements activos.
 *   <li>Cualquier fragmento listado por el custodian pero ausente de placements (o ya marcado
 *       PERDIDO en local) NO aparece en la whitelist → custodian lo purgará.
 *   <li>Actualiza {@code origin_custodian_health}: timestamp del último probe entrante + resetea
 *       contador de fallos.
 * </ol>
 *
 * <p>El servicio es la fuente de verdad para "what the origin wants to keep". No emite warns,
 * comportamiento normal del flujo. Warns y transiciones EN_RIESGO/PERDIDO los emite el {@code
 * OriginInverseProbeService} cuando un custodian deja de iniciar probes.
 */
public class OriginInboundKeepListService {

  private static final Logger LOGGER = LoggerFactory.getLogger(OriginInboundKeepListService.class);

  private final FragmentPlacementPort placementPort;
  private final OriginCustodianHealthPort custodianHealthPort;
  private final Clock clock;

  /** Creates service. */
  public OriginInboundKeepListService(
      final FragmentPlacementPort placementPort,
      final OriginCustodianHealthPort custodianHealthPort,
      final Clock clock) {
    if (placementPort == null || custodianHealthPort == null || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.placementPort = placementPort;
    this.custodianHealthPort = custodianHealthPort;
    this.clock = clock;
  }

  /**
   * Procesa el probe entrante y devuelve la whitelist.
   *
   * @param custodianNodeId identificador del custodian que firmó el probe (X-Node-Id)
   * @param custodianBaseUrl URL del custodian (resuelto por el adapter inbound desde el contexto;
   *     se cachea en {@code origin_custodian_health} para que el inverse worker no necesite
   *     cross-lookup)
   * @param requesterNodeId valor del campo body — debe matchear el nodeId local (este nodo es el
   *     que custodia los placements de los fragments)
   * @param fragmentIdsAtCustodian fragments que el custodian dice tener
   * @return whitelist de fragments a conservar
   */
  public List<String> processProbe(
      final String custodianNodeId,
      final String custodianBaseUrl,
      final String requesterNodeId,
      final List<String> fragmentIdsAtCustodian) {
    if (custodianNodeId == null || custodianNodeId.isBlank()) {
      throw new IllegalArgumentException("custodianNodeId must not be blank");
    }
    if (custodianBaseUrl == null || custodianBaseUrl.isBlank()) {
      throw new IllegalArgumentException("custodianBaseUrl must not be blank");
    }
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      throw new IllegalArgumentException("requesterNodeId must not be blank");
    }
    final List<String> incoming =
        fragmentIdsAtCustodian == null ? List.of() : List.copyOf(fragmentIdsAtCustodian);

    final Instant now = Instant.now(clock);
    // Lookup primario por cryptographic custodian id. Para placements
    // (upload directo cliente) el campo custodian_node_id se persiste como sentinel legacy
    // "peer@<baseUrl>" en lugar del id cryptographic, así que el primer query devuelve
    // vacío y el segundo (por baseUrl) trae las filas. Sin este fallback la keep-list
    // queda vacía y los custodians purgan todos los fragments del archivo recién subido.
    List<FragmentPlacement> placementsForCustodian =
        placementPort.findByCustodianNodeId(custodianNodeId);
    if (placementsForCustodian.isEmpty()) {
      placementsForCustodian = placementPort.findByCustodianBaseUrl(custodianBaseUrl);
    }
    final Set<String> incomingSet = Set.copyOf(incoming);

    int markedOk = 0;
    final List<String> keepList = new java.util.ArrayList<>();
    for (FragmentPlacement p : placementsForCustodian) {
      if (p.healthStatus() == FragmentHealthStatus.PERDIDO) {
        // Aunque el custodian afirme tener el fragment ahora, ya
        // tomamos la decisión de considerarlo perdido. NO se incluye en keepList → custodian lo
        // purgará.
        continue;
      }
      if (incomingSet.contains(p.fragmentId())) {
        // Custodian sí tiene el fragment → confirmar OK en placement.
        placementPort.updateHealth(p.withHealth(FragmentHealthStatus.OK, now));
        keepList.add(p.fragmentId());
        markedOk++;
      } else {
        // Custodian dice que no tiene un fragment que el origen sí esperaba — marcar PERDIDO
        // (warn grave).
        placementPort.updateHealth(p.withHealth(FragmentHealthStatus.PERDIDO, now));
        LOGGER
            .atWarn()
            .setMessage(
                "Custodian inbound probe: fragment expected at custodian was NOT in its list —"
                    + " marking PERDIDO")
            .addKeyValue("event", "FRAGMENT_NOT_FOUND_AT_CUSTODIAN")
            .addKeyValue("severity", "high")
            .addKeyValue("custodianNodeId", custodianNodeId)
            .addKeyValue("fragmentId", p.fragmentId())
            .log();
      }
    }

    custodianHealthPort.upsertOnInboundProbe(custodianNodeId, custodianBaseUrl, now);

    LOGGER
        .atDebug()
        .setMessage("Custodian inbound probe processed")
        .addKeyValue("custodianNodeId", custodianNodeId)
        .addKeyValue("fragmentsAtCustodian", incoming.size())
        .addKeyValue("placementsForCustodian", placementsForCustodian.size())
        .addKeyValue("keepListSize", keepList.size())
        .addKeyValue("markedOk", markedOk)
        .log();

    return keepList;
  }
}
