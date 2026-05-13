package es.ual.node.recovery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.recovery.adapters.out.memory.InMemoryCustodiedFileManifestPort;
import es.ual.node.recovery.application.TutorFileManifestCustodyService.StoreFileManifestRequest;
import es.ual.node.recovery.domain.CustodiedFileManifest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Listing-isolation tests for {@link TutorFileManifestCustodyService}: cada nodo recibe solo sus
 * propios manifests custodiados.
 */
class TutorFileManifestCustodyListingTest {

  private static final String NODE_A = "node-a";
  private static final String NODE_B = "node-b";
  private static final String PUB_KEY_A = "pubkey-a";
  private static final String PUB_KEY_B = "pubkey-b";
  private static final String HASH =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final Instant NOW = Instant.parse("2026-04-27T10:00:00Z");

  @Test
  void listingIsolatesEachNodeManifests() {
    final TutorFileManifestCustodyService service = newService(List.of(PUB_KEY_A, PUB_KEY_B));

    service.store(req("file-a-1", NODE_A, PUB_KEY_A, "/a"));
    service.store(req("file-a-2", NODE_A, PUB_KEY_A, "/a"));
    service.store(req("file-b-1", NODE_B, PUB_KEY_B, "/b"));

    final List<CustodiedFileManifest> aManifests = service.listByRequesterNodeId(NODE_A);
    final List<CustodiedFileManifest> bManifests = service.listByRequesterNodeId(NODE_B);

    assertEquals(2, aManifests.size());
    assertEquals(1, bManifests.size());
    assertTrue(
        aManifests.stream().allMatch(m -> NODE_A.equals(m.requesterNodeId())),
        "all manifests for A must belong to A");
    assertTrue(
        bManifests.stream().allMatch(m -> NODE_B.equals(m.requesterNodeId())),
        "all manifests for B must belong to B");
  }

  @Test
  void listingReturnsEmptyForNodeWithoutCustodiedManifests() {
    final TutorFileManifestCustodyService service = newService(List.of(PUB_KEY_A));
    service.store(req("file-a-1", NODE_A, PUB_KEY_A, "/a"));

    assertTrue(service.listByRequesterNodeId("node-unknown").isEmpty());
  }

  private TutorFileManifestCustodyService newService(final List<String> whitelist) {
    final NodeTopologyProperties topology = new NodeTopologyProperties();
    topology.setTutorAcceptedPublicKeys(whitelist);
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    return new TutorFileManifestCustodyService(
        topology, new InMemoryCustodiedFileManifestPort(), clock);
  }

  private StoreFileManifestRequest req(
      final String suffix, final String nodeId, final String pubKey, final String dirPath) {
    return new StoreFileManifestRequest(
        java.util.UUID.nameUUIDFromBytes(suffix.getBytes()).toString(),
        nodeId,
        pubKey,
        dirPath,
        suffix + ".bin",
        HASH,
        4096L,
        null,
        null,
        4,
        1024L,
        6,
        4,
        List.of(HASH, HASH, HASH, HASH),
        null,
        null);
  }
}
