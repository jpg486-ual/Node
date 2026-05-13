package es.ual.node.recovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.recovery.adapters.out.memory.InMemoryCustodiedFileManifestPort;
import es.ual.node.recovery.domain.CustodiedFileManifest;
import es.ual.node.recovery.ports.out.RemoteOriginKeepListClientPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TutorManifestKeepListService}. Cubre whitelist invertida y la regla "tutor NO
 * purga si origen no responde".
 */
class TutorManifestKeepListServiceTest {

  private static final String NODE_A = "node-a";
  private static final String NODE_A_BASE_URL = "http://node-a:8080";
  private static final String FILE_1 = "00000000-0000-0000-0000-000000000001";
  private static final String FILE_2 = "00000000-0000-0000-0000-000000000002";
  private static final String FILE_3 = "00000000-0000-0000-0000-000000000003";
  private static final Instant NOW = Instant.parse("2026-05-04T12:00:00Z");

  @Test
  void runOnce_purgesManifestsNotInWhitelistAndKeepsOthers() {
    // Origen responde con fileIds {FILE_1, FILE_3}. Tutor tiene {FILE_1, FILE_2, FILE_3}.
    // Esperado: FILE_2 purgado; los otros se mantienen.
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    port.save(manifest(FILE_1, NODE_A));
    port.save(manifest(FILE_2, NODE_A));
    port.save(manifest(FILE_3, NODE_A));

    final RemoteOriginKeepListClientPort client = url -> List.of(FILE_1, FILE_3);
    final TutorManifestKeepListService service =
        new TutorManifestKeepListService(
            port, client, topologyWith(NODE_A, NODE_A_BASE_URL), clockAt(NOW));

    final TutorManifestKeepListService.CycleSummary summary = service.runOnce();

    assertThat(summary.probedNodes()).isEqualTo(1);
    assertThat(summary.purgedManifests()).isEqualTo(1);
    assertThat(summary.keptManifests()).isEqualTo(2);
    assertThat(summary.silentNodes()).isZero();
    assertThat(port.findByFileId(FILE_1)).isPresent();
    assertThat(port.findByFileId(FILE_2)).isEmpty();
    assertThat(port.findByFileId(FILE_3)).isPresent();
  }

  @Test
  void runOnce_doesNotPurgeWhenOriginIsSilent() {
    // Si origen no responde, tutor NUNCA purga (manifest preservation guard).
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    port.save(manifest(FILE_1, NODE_A));
    port.save(manifest(FILE_2, NODE_A));

    final RemoteOriginKeepListClientPort failingClient =
        url -> {
          throw new IllegalStateException("origin unreachable");
        };
    final TutorManifestKeepListService service =
        new TutorManifestKeepListService(
            port, failingClient, topologyWith(NODE_A, NODE_A_BASE_URL), clockAt(NOW));

    final TutorManifestKeepListService.CycleSummary summary = service.runOnce();

    assertThat(summary.silentNodes()).isEqualTo(1);
    assertThat(summary.purgedManifests()).isZero();
    assertThat(port.findByFileId(FILE_1)).isPresent();
    assertThat(port.findByFileId(FILE_2)).isPresent();
  }

  @Test
  void runOnce_marksSupervisedCheckOkOnSuccessfulProbe() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    port.save(manifest(FILE_1, NODE_A));
    // Manifest llega con consecutiveOriginFailures=2 (probes previos fallaron).
    port.markSupervisedCheckFailed(NODE_A, NOW.minusSeconds(60));
    port.markSupervisedCheckFailed(NODE_A, NOW.minusSeconds(30));

    final RemoteOriginKeepListClientPort client = url -> List.of(FILE_1);
    final TutorManifestKeepListService service =
        new TutorManifestKeepListService(
            port, client, topologyWith(NODE_A, NODE_A_BASE_URL), clockAt(NOW));

    service.runOnce();

    final CustodiedFileManifest after = port.findByFileId(FILE_1).orElseThrow();
    assertThat(after.lastSupervisedCheckAt()).isEqualTo(NOW);
    assertThat(after.consecutiveOriginFailures()).isZero();
  }

  @Test
  void runOnce_incrementsConsecutiveFailuresOnSilentOrigin() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    port.save(manifest(FILE_1, NODE_A));

    final RemoteOriginKeepListClientPort failingClient =
        url -> {
          throw new IllegalStateException("origin unreachable");
        };
    final TutorManifestKeepListService service =
        new TutorManifestKeepListService(
            port, failingClient, topologyWith(NODE_A, NODE_A_BASE_URL), clockAt(NOW));

    service.runOnce();

    assertThat(port.findByFileId(FILE_1).orElseThrow().consecutiveOriginFailures()).isEqualTo(1);
    assertThat(port.findByFileId(FILE_1).orElseThrow().lastSupervisedCheckAt()).isEqualTo(NOW);
  }

  @Test
  void runOnce_skipsSupervisedNodeWithoutBaseUrlConfigured() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    port.save(manifest(FILE_1, NODE_A));

    final RemoteOriginKeepListClientPort client = url -> List.of();
    // Topology vacía: no hay baseUrl para NODE_A.
    final TutorManifestKeepListService service =
        new TutorManifestKeepListService(port, client, new NodeTopologyProperties(), clockAt(NOW));

    final TutorManifestKeepListService.CycleSummary summary = service.runOnce();

    assertThat(summary.probedNodes()).isZero();
    assertThat(port.findByFileId(FILE_1)).isPresent();
  }

  @Test
  void runOnce_iteratesEverySupervisedNode() {
    final String NODE_B = "node-b";
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    port.save(manifest(FILE_1, NODE_A));
    port.save(manifest(FILE_2, NODE_B));

    final NodeTopologyProperties topology = new NodeTopologyProperties();
    final Map<String, String> map = new HashMap<>();
    map.put(NODE_A, NODE_A_BASE_URL);
    map.put(NODE_B, "http://node-b:8080");
    topology.setSupervisedBaseUrls(map);

    final RemoteOriginKeepListClientPort client =
        url -> {
          // Origen mantiene todo: no purgas.
          return url.contains("node-a") ? List.of(FILE_1) : List.of(FILE_2);
        };
    final TutorManifestKeepListService service =
        new TutorManifestKeepListService(port, client, topology, clockAt(NOW));

    final TutorManifestKeepListService.CycleSummary summary = service.runOnce();

    assertThat(summary.probedNodes()).isEqualTo(2);
    assertThat(summary.purgedManifests()).isZero();
    assertThat(summary.keptManifests()).isEqualTo(2);
  }

  private NodeTopologyProperties topologyWith(final String nodeId, final String baseUrl) {
    final NodeTopologyProperties topology = new NodeTopologyProperties();
    final Map<String, String> map = new HashMap<>();
    map.put(nodeId, baseUrl);
    topology.setSupervisedBaseUrls(map);
    return topology;
  }

  private CustodiedFileManifest manifest(final String fileId, final String nodeId) {
    return new CustodiedFileManifest(
        fileId,
        nodeId,
        "pubkey",
        "/docs",
        "f.bin",
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        4096L,
        null,
        null,
        4,
        1024L,
        6,
        4,
        List.of("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64)),
        null,
        null,
        NOW.minusSeconds(3600));
  }

  private Clock clockAt(final Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }
}
