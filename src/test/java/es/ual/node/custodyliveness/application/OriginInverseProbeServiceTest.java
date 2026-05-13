package es.ual.node.custodyliveness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import es.ual.node.custodyliveness.adapters.out.memory.InMemoryOriginCustodianHealthPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFragmentPlacementPort;
import es.ual.node.filesystem.domain.FragmentHealthStatus;
import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.fragmentstorage.domain.CustodyInventoryItem;
import es.ual.node.fragmentstorage.ports.out.RemoteCustodyInventoryClientPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests del probe inverso del origen.
 *
 * <ul>
 *   <li>1ª no-respuesta del custodian → placements EN_RIESGO + WARN.
 *   <li>Tras N intentos consecutivos sin respuesta → placements PERDIDO + WARN crítico.
 *   <li>Custodian responde inventario: OK presentes; PERDIDO los esperados que NO aparezcan.
 *   <li>Decommission irrevocable: PERDIDO permanece PERDIDO si custodian vuelve.
 * </ul>
 */
class OriginInverseProbeServiceTest {

  private static final String CUSTODIAN_ID = "node-custodian-aaa";
  private static final String CUSTODIAN_URL = "http://node-custodian:8080";
  private static final String SELF_ID = "node-self-bbb";
  private static final String FILE_ID = "11111111-1111-1111-1111-111111111111";
  private static final String CHECKSUM =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  private static final Instant NOW = Instant.parse("2026-05-04T12:00:00Z");
  // Last inbound probe lo bastante antiguo como para que el custodian se considere silente.
  private static final Instant SILENT_SINCE = NOW.minusSeconds(3600);

  private final InMemoryFragmentPlacementPort placementPort = new InMemoryFragmentPlacementPort();
  private final InMemoryOriginCustodianHealthPort custodianHealthPort =
      new InMemoryOriginCustodianHealthPort();
  private final RecordingInventoryClient inventoryClient = new RecordingInventoryClient();
  private final CustodyLivenessProperties properties = new CustodyLivenessProperties();

  private OriginInverseProbeService service;

  @BeforeEach
  void setUp() {
    properties.setBaseIntervalSeconds(60L);
    properties.setInverseProbeToleranceSeconds(30L);
    properties.setUnresponsiveThresholdAttempts(3);
    service =
        new OriginInverseProbeService(
            placementPort,
            custodianHealthPort,
            inventoryClient,
            properties,
            SELF_ID,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void firstNoResponseMarksPlacementsAtRiskAndIncrementsFailures() {
    custodianHealthPort.upsertOnInboundProbe(CUSTODIAN_ID, CUSTODIAN_URL, SILENT_SINCE);
    placementPort.save(placement("frag-A", FragmentHealthStatus.OK));
    placementPort.save(placement("frag-B", FragmentHealthStatus.OK));
    inventoryClient.respondWith(List.of()); // sin respuesta válida → tratado como no-respuesta

    final OriginInverseProbeService.CycleSummary summary = service.runOnce();

    assertEquals(1, summary.probedCustodians());
    assertEquals(2, summary.markedAtRisk());
    assertEquals(0, summary.markedLost());
    assertEquals(
        FragmentHealthStatus.EN_RIESGO,
        placementPort.findByFileId(FILE_ID).stream()
            .filter(p -> p.fragmentId().equals("frag-A"))
            .findFirst()
            .orElseThrow()
            .healthStatus());
    assertEquals(1, custodianHealthPort.findById(CUSTODIAN_ID).orElseThrow().consecutiveFailures());
  }

  @Test
  void crossingUnresponsiveThresholdMarksPlacementsLost() {
    // Pre-state: custodian ya tiene 2 fallos consecutivos previos.
    custodianHealthPort.save(
        new es.ual.node.custodyliveness.domain.OriginCustodianHealth(
            CUSTODIAN_ID, CUSTODIAN_URL, SILENT_SINCE, 2, SILENT_SINCE));
    placementPort.save(placement("frag-A", FragmentHealthStatus.EN_RIESGO));
    placementPort.save(placement("frag-B", FragmentHealthStatus.EN_RIESGO));
    inventoryClient.respondWith(List.of()); // 3ª no-respuesta consecutiva

    final OriginInverseProbeService.CycleSummary summary = service.runOnce();

    assertEquals(2, summary.markedLost(), "ambos placements transicionan PERDIDO");
    assertEquals(
        FragmentHealthStatus.PERDIDO,
        placementPort.findByFileId(FILE_ID).stream()
            .filter(p -> p.fragmentId().equals("frag-A"))
            .findFirst()
            .orElseThrow()
            .healthStatus());
  }

  @Test
  void custodianRespondsConfirmsOkAndMarksMissingAsPerdido() {
    custodianHealthPort.upsertOnInboundProbe(CUSTODIAN_ID, CUSTODIAN_URL, SILENT_SINCE);
    placementPort.save(placement("frag-A", FragmentHealthStatus.EN_RIESGO));
    placementPort.save(placement("frag-B", FragmentHealthStatus.EN_RIESGO));
    inventoryClient.respondWith(List.of(inventoryItem("frag-A")));

    final OriginInverseProbeService.CycleSummary summary = service.runOnce();

    assertEquals(1, summary.markedOk());
    assertEquals(1, summary.markedLost());
    assertEquals(
        FragmentHealthStatus.OK,
        placementPort.findByFileId(FILE_ID).stream()
            .filter(p -> p.fragmentId().equals("frag-A"))
            .findFirst()
            .orElseThrow()
            .healthStatus());
    assertEquals(
        FragmentHealthStatus.PERDIDO,
        placementPort.findByFileId(FILE_ID).stream()
            .filter(p -> p.fragmentId().equals("frag-B"))
            .findFirst()
            .orElseThrow()
            .healthStatus());
  }

  @Test
  void perdidoStaysPerdidoWhenCustodianRespondsLater() {
    custodianHealthPort.upsertOnInboundProbe(CUSTODIAN_ID, CUSTODIAN_URL, SILENT_SINCE);
    placementPort.save(placement("frag-A", FragmentHealthStatus.PERDIDO));
    inventoryClient.respondWith(List.of(inventoryItem("frag-A")));

    final OriginInverseProbeService.CycleSummary summary = service.runOnce();

    // Filter en service: PERDIDO no entra en `expected` → custodian responde pero el placement
    // no participa. markedOk=0, markedLost=0.
    assertEquals(0, summary.markedOk());
    assertEquals(0, summary.markedLost());
    assertEquals(
        FragmentHealthStatus.PERDIDO, placementPort.findByFileId(FILE_ID).get(0).healthStatus());
  }

  @Test
  void custodianNotSilentIsNotProbed() {
    // Last inbound probe muy reciente → no es silente.
    custodianHealthPort.upsertOnInboundProbe(CUSTODIAN_ID, CUSTODIAN_URL, NOW.minusSeconds(5));
    placementPort.save(placement("frag-A", FragmentHealthStatus.OK));

    final OriginInverseProbeService.CycleSummary summary = service.runOnce();

    assertEquals(0, summary.probedCustodians());
    assertEquals(0, inventoryClient.callCount);
  }

  @Test
  void silentCustodian_resolvesPlacementsViaSentinelBaseUrlFallback() {
    custodianHealthPort.upsertOnInboundProbe(CUSTODIAN_ID, CUSTODIAN_URL, SILENT_SINCE);
    // Placement persistido con sentinel legacy "peer@<baseUrl>" en custodian_node_id.
    final String sentinelCustodianId = "peer@" + CUSTODIAN_URL;
    placementPort.save(
        placementWithCustodian("frag-A", sentinelCustodianId, FragmentHealthStatus.OK));
    placementPort.save(
        placementWithCustodian("frag-B", sentinelCustodianId, FragmentHealthStatus.OK));
    inventoryClient.respondWith(List.of()); // custodian no responde

    final OriginInverseProbeService.CycleSummary summary = service.runOnce();

    assertEquals(1, summary.probedCustodians(), "el custodian silent se procesa");
    assertEquals(
        2, summary.markedAtRisk(), "ambos placements transicionan EN_RIESGO via fallback baseUrl");
  }

  // ---------- Helpers ----------

  private FragmentPlacement placement(final String fragmentId, final FragmentHealthStatus status) {
    return placementWithCustodian(fragmentId, CUSTODIAN_ID, status);
  }

  private FragmentPlacement placementWithCustodian(
      final String fragmentId, final String custodianNodeId, final FragmentHealthStatus status) {
    return new FragmentPlacement(
        FILE_ID,
        fragmentId,
        0,
        0,
        false,
        custodianNodeId,
        CUSTODIAN_URL,
        "agreement-" + fragmentId,
        CHECKSUM,
        1024L,
        NOW,
        status,
        null,
        0);
  }

  private CustodyInventoryItem inventoryItem(final String fragmentId) {
    return new CustodyInventoryItem(
        fragmentId, "agreement-" + fragmentId, 1024L, CHECKSUM, NOW.plusSeconds(7200L), 7200L);
  }

  private static final class RecordingInventoryClient implements RemoteCustodyInventoryClientPort {
    int callCount = 0;
    private List<CustodyInventoryItem> nextResponse = List.of();

    void respondWith(final List<CustodyInventoryItem> items) {
      this.nextResponse = items;
    }

    @Override
    public List<CustodyInventoryItem> fetchInventory(
        final String custodianBaseUrl, final String nodeId) {
      callCount++;
      return nextResponse;
    }
  }
}
