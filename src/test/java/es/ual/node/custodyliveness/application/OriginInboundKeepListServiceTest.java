package es.ual.node.custodyliveness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.custodyliveness.adapters.out.memory.InMemoryOriginCustodianHealthPort;
import es.ual.node.custodyliveness.domain.OriginCustodianHealth;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFragmentPlacementPort;
import es.ual.node.filesystem.domain.FragmentHealthStatus;
import es.ual.node.filesystem.domain.FragmentPlacement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests del handler del origen para inbound keep-list (probe inverso custodian → origen).
 *
 * <ul>
 *   <li>Whitelist puro: el origen devuelve solo los fragments que sigue queriendo.
 *   <li>Confirmación OK: placements en la whitelist se marcan {@code OK} con {@code last_check_at}.
 *   <li>Warn grave: placement esperado que el custodian dice NO tener → {@code PERDIDO}.
 *   <li>Decommission irrevocable: PERDIDO no vuelve a OK aunque el custodian lo afirme.
 *   <li>Tracking de probes entrantes en {@code origin_custodian_health}.
 * </ul>
 */
class OriginInboundKeepListServiceTest {

  private static final String CUSTODIAN_ID = "node-custodian-aaa";
  private static final String CUSTODIAN_URL = "http://node-custodian:8080";
  private static final String SELF_ID = "node-self-bbb";
  private static final String FILE_ID = "11111111-1111-1111-1111-111111111111";
  private static final String CHECKSUM =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final Instant NOW = Instant.parse("2026-05-04T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final InMemoryFragmentPlacementPort placementPort = new InMemoryFragmentPlacementPort();
  private final InMemoryOriginCustodianHealthPort custodianHealthPort =
      new InMemoryOriginCustodianHealthPort();
  private final OriginInboundKeepListService service =
      new OriginInboundKeepListService(placementPort, custodianHealthPort, CLOCK);

  @Test
  void returnsWhitelistOfPlacementsForCustodianMatchingIncomingFragments() {
    placementPort.save(placement("frag-A", FragmentHealthStatus.OK));
    placementPort.save(placement("frag-B", FragmentHealthStatus.OK));

    final List<String> keep =
        service.processProbe(
            CUSTODIAN_ID, CUSTODIAN_URL, SELF_ID, List.of("frag-A", "frag-B", "frag-extra"));

    assertEquals(List.of("frag-A", "frag-B"), keep);
  }

  @Test
  void marksMatchingPlacementsAsOkWithCheckTimestamp() {
    placementPort.save(placement("frag-A", FragmentHealthStatus.EN_RIESGO));

    service.processProbe(CUSTODIAN_ID, CUSTODIAN_URL, SELF_ID, List.of("frag-A"));

    final FragmentPlacement updated = placementPort.findByFileId(FILE_ID).get(0);
    assertEquals(FragmentHealthStatus.OK, updated.healthStatus());
    assertEquals(NOW, updated.lastCheckAt());
    assertEquals(0, updated.consecutiveFailures());
  }

  @Test
  void marksAsPerdidoWhenCustodianDoesNotListExpectedFragment() {
    placementPort.save(placement("frag-A", FragmentHealthStatus.OK));
    placementPort.save(placement("frag-B", FragmentHealthStatus.OK));

    // Custodian sólo afirma tener frag-A; frag-B se marca PERDIDO.
    service.processProbe(CUSTODIAN_ID, CUSTODIAN_URL, SELF_ID, List.of("frag-A"));

    final var fragB =
        placementPort.findByFileId(FILE_ID).stream()
            .filter(p -> p.fragmentId().equals("frag-B"))
            .findFirst()
            .orElseThrow();
    assertEquals(FragmentHealthStatus.PERDIDO, fragB.healthStatus());
  }

  @Test
  void perdidoPlacementStaysPerdidoEvenWhenCustodianAffirmsIt() {
    placementPort.save(placement("frag-A", FragmentHealthStatus.PERDIDO));

    final List<String> keep =
        service.processProbe(CUSTODIAN_ID, CUSTODIAN_URL, SELF_ID, List.of("frag-A"));

    // Decommission irrevocable: NO se incluye en keep-list, custodian lo purgará.
    assertFalse(keep.contains("frag-A"));
    final FragmentPlacement still = placementPort.findByFileId(FILE_ID).get(0);
    assertEquals(FragmentHealthStatus.PERDIDO, still.healthStatus());
  }

  @Test
  void recordsInboundProbeInCustodianHealthTable() {
    placementPort.save(placement("frag-A", FragmentHealthStatus.OK));

    service.processProbe(CUSTODIAN_ID, CUSTODIAN_URL, SELF_ID, List.of("frag-A"));

    final Optional<OriginCustodianHealth> tracked = custodianHealthPort.findById(CUSTODIAN_ID);
    assertTrue(tracked.isPresent());
    assertEquals(CUSTODIAN_URL, tracked.get().custodianBaseUrl());
    assertEquals(NOW, tracked.get().lastInboundProbeAt());
    assertEquals(0, tracked.get().consecutiveFailures());
  }

  @Test
  void emptyIncomingListReturnsEmptyKeepList() {
    placementPort.save(placement("frag-A", FragmentHealthStatus.OK));

    final List<String> keep = service.processProbe(CUSTODIAN_ID, CUSTODIAN_URL, SELF_ID, List.of());

    // Sin fragments en la request → keep-list vacío. Pero igual el placement frag-A se marca
    // PERDIDO porque "el custodian dice no tener nada".
    assertNotNull(keep);
    assertTrue(keep.isEmpty());
    final FragmentPlacement still = placementPort.findByFileId(FILE_ID).get(0);
    assertEquals(FragmentHealthStatus.PERDIDO, still.healthStatus());
  }

  // ---------- placements con custodianNodeId legacy (peer@<baseUrl>) ----------

  @Test
  void fallsBackToBaseUrlWhenPlacementCustodianIdIsLegacyPeerSentinel() {
    // Reproduce el bug: FileContentDistributionService escribe
    // custodian_node_id="peer@<baseUrl>" en lugar del cryptographic id. El probe inbound
    // llega firmado por el cryptographic id (CUSTODIAN_ID). Sin el fallback por baseUrl,
    // findByCustodianNodeId(CUSTODIAN_ID) devuelve vacío, la keep-list resulta vacía y el
    // custodian purga el fragment del archivo recién subido.
    placementPort.save(legacyPlacement("frag-A", FragmentHealthStatus.OK));

    final List<String> keep =
        service.processProbe(CUSTODIAN_ID, CUSTODIAN_URL, SELF_ID, List.of("frag-A"));

    assertEquals(List.of("frag-A"), keep);
    final FragmentPlacement marked = placementPort.findByFileId(FILE_ID).get(0);
    assertEquals(FragmentHealthStatus.OK, marked.healthStatus());
    assertEquals(NOW, marked.lastCheckAt());
  }

  @Test
  void fallbackKeepListEmptyWhenBaseUrlAlsoMisses() {
    // Sin placements en absoluto: keep-list vacía es la respuesta correcta. no es un bug.
    final List<String> keep =
        service.processProbe(CUSTODIAN_ID, CUSTODIAN_URL, SELF_ID, List.of("frag-A"));
    assertTrue(keep.isEmpty());
  }

  // ---------- Helpers ----------

  private FragmentPlacement legacyPlacement(
      final String fragmentId, final FragmentHealthStatus status) {
    return new FragmentPlacement(
        FILE_ID,
        fragmentId,
        0,
        0,
        false,
        "peer@" + CUSTODIAN_URL,
        CUSTODIAN_URL,
        "client-upload-" + java.util.UUID.randomUUID(),
        CHECKSUM,
        1024L,
        NOW,
        status,
        null,
        0);
  }

  private FragmentPlacement placement(final String fragmentId, final FragmentHealthStatus status) {
    return new FragmentPlacement(
        FILE_ID,
        fragmentId,
        0,
        0,
        false,
        CUSTODIAN_ID,
        CUSTODIAN_URL,
        "agreement-" + fragmentId,
        CHECKSUM,
        1024L,
        NOW,
        status,
        null,
        0);
  }
}
