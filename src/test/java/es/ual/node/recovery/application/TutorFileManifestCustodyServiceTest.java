package es.ual.node.recovery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.recovery.adapters.out.memory.InMemoryCustodiedFileManifestPort;
import es.ual.node.recovery.application.TutorFileManifestCustodyService.StoreFileManifestRequest;
import es.ual.node.recovery.domain.CustodiedFileManifest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TutorFileManifestCustodyService}. El tutor no purga manifests por TTL ni
 * acepta extends.
 */
class TutorFileManifestCustodyServiceTest {

  private static final String FILE_ID = "0d7f64c2-97cc-4400-a2a3-b3af056f85a1";
  private static final String FILE_ID_2 = "1d7f64c2-97cc-4400-a2a3-b3af056f85a2";
  private static final String FILE_ID_3 = "2d7f64c2-97cc-4400-a2a3-b3af056f85a3";
  private static final String NODE_ID = "node-requester-1";
  private static final String PUB_KEY = "pubkey-base64";
  private static final String HASH =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final Instant NOW = Instant.parse("2026-04-27T10:00:00Z");

  @Test
  void storesManifestWhenRequesterPublicKeyIsWhitelisted() {
    final TutorFileManifestCustodyService service = newService(List.of(PUB_KEY));

    final CustodiedFileManifest stored = service.store(validRequest());

    assertEquals(FILE_ID, stored.fileId());
    assertEquals(NODE_ID, stored.requesterNodeId());
    assertEquals("/foo/bar", stored.directoryPath());
    assertEquals("video.mov", stored.originalFileName());
    assertEquals(NOW, stored.storedAt());
    // lastSupervisedCheckAt arranca null + consecutiveOriginFailures en 0.
    assertNull(stored.lastSupervisedCheckAt());
    assertEquals(0, stored.consecutiveOriginFailures());
  }

  @Test
  void rejectsManifestWhenRequesterPublicKeyNotWhitelisted() {
    final TutorFileManifestCustodyService service = newService(List.of("other-pubkey"));

    assertThrows(SecurityException.class, () -> service.store(validRequest()));
  }

  @Test
  void listByRequesterNodeIdReturnsStoredManifests() {
    final TutorFileManifestCustodyService service = newService(List.of(PUB_KEY));
    service.store(validRequest());

    final List<CustodiedFileManifest> list = service.listByRequesterNodeId(NODE_ID);

    assertEquals(1, list.size());
    assertEquals(FILE_ID, list.get(0).fileId());
  }

  @Test
  void listByRequesterNodeIdReturnsEmptyForUnknownNode() {
    final TutorFileManifestCustodyService service = newService(List.of(PUB_KEY));
    service.store(validRequest());

    assertTrue(service.listByRequesterNodeId("other-node").isEmpty());
  }

  // ---------- delete ----------

  @Test
  void deleteRemovesManifestWhenCallerOwnsIt() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    final TutorFileManifestCustodyService service = serviceWithPort(port, List.of(PUB_KEY));
    service.store(validRequest());

    final boolean deleted = service.delete(FILE_ID, NODE_ID);

    assertTrue(deleted);
    assertTrue(port.findByFileId(FILE_ID).isEmpty());
  }

  @Test
  void deleteThrowsNoSuchElementWhenManifestMissing() {
    final TutorFileManifestCustodyService service =
        serviceWithPort(new InMemoryCustodiedFileManifestPort(), List.of(PUB_KEY));

    assertThrows(NoSuchElementException.class, () -> service.delete(FILE_ID, NODE_ID));
  }

  @Test
  void deleteThrowsSecurityWhenCallerDoesNotOwnManifest() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    final TutorFileManifestCustodyService service = serviceWithPort(port, List.of(PUB_KEY));
    service.store(validRequest());

    assertThrows(SecurityException.class, () -> service.delete(FILE_ID, "other-node"));
    assertEquals(1, port.findByRequesterNodeId(NODE_ID).size());
  }

  @Test
  void deleteRejectsBlankInputs() {
    final TutorFileManifestCustodyService service =
        serviceWithPort(new InMemoryCustodiedFileManifestPort(), List.of(PUB_KEY));

    assertThrows(IllegalArgumentException.class, () -> service.delete("", NODE_ID));
    assertThrows(IllegalArgumentException.class, () -> service.delete(null, NODE_ID));
    assertThrows(IllegalArgumentException.class, () -> service.delete(FILE_ID, ""));
    assertThrows(IllegalArgumentException.class, () -> service.delete(FILE_ID, null));
  }

  // ---------- updatePath ----------

  @Test
  void updatePath_replacesDirectoryPathAndFileName() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    final TutorFileManifestCustodyService service = serviceWithPort(port, List.of(PUB_KEY));
    service.store(validRequest());

    final CustodiedFileManifest updated =
        service.updatePath(FILE_ID, NODE_ID, "/jose/B", "renamed.mov");

    assertEquals("/jose/B", updated.directoryPath());
    assertEquals("renamed.mov", updated.originalFileName());
    assertEquals("/jose/B", port.findByFileId(FILE_ID).orElseThrow().directoryPath());
  }

  @Test
  void updatePath_rejectsCallerWhoIsNotOwner() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    final TutorFileManifestCustodyService service = serviceWithPort(port, List.of(PUB_KEY));
    service.store(validRequest());

    assertThrows(
        SecurityException.class,
        () -> service.updatePath(FILE_ID, "intruder-node", "/jose/B", "renamed.mov"));
    assertEquals("/foo/bar", port.findByFileId(FILE_ID).orElseThrow().directoryPath());
  }

  @Test
  void updatePath_rejectsInvalidDirectoryPathRegex() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    final TutorFileManifestCustodyService service = serviceWithPort(port, List.of(PUB_KEY));
    service.store(validRequest());

    assertThrows(
        IllegalArgumentException.class,
        () -> service.updatePath(FILE_ID, NODE_ID, "jose/B", "renamed.mov"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updatePath(FILE_ID, NODE_ID, "/jose/B", "sub/renamed.mov"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updatePath(FILE_ID, NODE_ID, "", "renamed.mov"));
    assertThrows(
        IllegalArgumentException.class, () -> service.updatePath(FILE_ID, NODE_ID, "/jose/B", ""));
  }

  @Test
  void updatePath_throwsWhenManifestNotFound() {
    final TutorFileManifestCustodyService service =
        serviceWithPort(new InMemoryCustodiedFileManifestPort(), List.of(PUB_KEY));

    assertThrows(
        NoSuchElementException.class,
        () -> service.updatePath(FILE_ID, NODE_ID, "/jose/B", "renamed.mov"));
  }

  // ---------- updatePathBulk ----------

  @Test
  void updatePathBulk_replacesAllAtomically() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    final TutorFileManifestCustodyService service = serviceWithPort(port, List.of(PUB_KEY));
    service.store(validRequest());
    service.store(secondRequest());

    final List<CustodiedFileManifest> updated =
        service.updatePathBulk(
            NODE_ID,
            List.of(
                new TutorFileManifestCustodyService.BulkUpdateEntry(
                    FILE_ID, "/jose/B", "first.mov"),
                new TutorFileManifestCustodyService.BulkUpdateEntry(
                    FILE_ID_2, "/jose/C", "renamed-second.mov")));

    assertEquals(2, updated.size());
    assertEquals("/jose/B", port.findByFileId(FILE_ID).orElseThrow().directoryPath());
    assertEquals("first.mov", port.findByFileId(FILE_ID).orElseThrow().originalFileName());
    assertEquals("/jose/C", port.findByFileId(FILE_ID_2).orElseThrow().directoryPath());
    assertEquals(
        "renamed-second.mov", port.findByFileId(FILE_ID_2).orElseThrow().originalFileName());
  }

  @Test
  void updatePathBulk_rollsBackWhenAnyOwnershipFails() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    final TutorFileManifestCustodyService service = serviceWithPort(port, List.of(PUB_KEY));
    service.store(validRequest());
    port.save(intruderManifest());

    assertThrows(
        SecurityException.class,
        () ->
            service.updatePathBulk(
                NODE_ID,
                List.of(
                    new TutorFileManifestCustodyService.BulkUpdateEntry(
                        FILE_ID, "/jose/B", "first.mov"),
                    new TutorFileManifestCustodyService.BulkUpdateEntry(
                        FILE_ID_3, "/jose/D", "stolen.mov"))));

    assertEquals("/foo/bar", port.findByFileId(FILE_ID).orElseThrow().directoryPath());
    assertEquals("/intruder/A", port.findByFileId(FILE_ID_3).orElseThrow().directoryPath());
  }

  @Test
  void updatePathBulk_rejectsInvalidRegexBeforeAnyWrite() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    final TutorFileManifestCustodyService service = serviceWithPort(port, List.of(PUB_KEY));
    service.store(validRequest());
    service.store(secondRequest());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.updatePathBulk(
                NODE_ID,
                List.of(
                    new TutorFileManifestCustodyService.BulkUpdateEntry(
                        FILE_ID, "/jose/B", "first.mov"),
                    new TutorFileManifestCustodyService.BulkUpdateEntry(
                        FILE_ID_2, "no-leading-slash", "second.mov"))));

    assertEquals("/foo/bar", port.findByFileId(FILE_ID).orElseThrow().directoryPath());
    assertEquals("/foo/bar", port.findByFileId(FILE_ID_2).orElseThrow().directoryPath());
  }

  @Test
  void updatePathBulk_throwsWhenAnyManifestNotFound() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    final TutorFileManifestCustodyService service = serviceWithPort(port, List.of(PUB_KEY));
    service.store(validRequest());

    assertThrows(
        NoSuchElementException.class,
        () ->
            service.updatePathBulk(
                NODE_ID,
                List.of(
                    new TutorFileManifestCustodyService.BulkUpdateEntry(
                        FILE_ID, "/jose/B", "first.mov"),
                    new TutorFileManifestCustodyService.BulkUpdateEntry(
                        FILE_ID_2, "/jose/C", "second.mov"))));

    assertEquals("/foo/bar", port.findByFileId(FILE_ID).orElseThrow().directoryPath());
  }

  // ---------- deleteBulk ----------

  @Test
  void deleteBulk_removesAllOwnedManifests() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    final TutorFileManifestCustodyService service = serviceWithPort(port, List.of(PUB_KEY));
    service.store(validRequest());
    service.store(secondRequest());

    final TutorFileManifestCustodyService.DeleteBulkResult result =
        service.deleteBulk(NODE_ID, List.of(FILE_ID, FILE_ID_2));

    assertEquals(2, result.deletedCount());
    assertEquals(0, result.missingCount());
    assertTrue(port.findByFileId(FILE_ID).isEmpty());
    assertTrue(port.findByFileId(FILE_ID_2).isEmpty());
  }

  @Test
  void deleteBulk_isIdempotentForMissingFileIds() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    final TutorFileManifestCustodyService service = serviceWithPort(port, List.of(PUB_KEY));
    service.store(validRequest());

    final TutorFileManifestCustodyService.DeleteBulkResult result =
        service.deleteBulk(NODE_ID, List.of(FILE_ID, FILE_ID_2, FILE_ID_3));

    assertEquals(1, result.deletedCount());
    assertEquals(2, result.missingCount());
    assertTrue(port.findByFileId(FILE_ID).isEmpty());
  }

  @Test
  void deleteBulk_rejectsWhenAnyFileIdNotOwnedByCaller() {
    final InMemoryCustodiedFileManifestPort port = new InMemoryCustodiedFileManifestPort();
    final TutorFileManifestCustodyService service = serviceWithPort(port, List.of(PUB_KEY));
    service.store(validRequest());
    port.save(intruderManifest());

    assertThrows(
        SecurityException.class, () -> service.deleteBulk(NODE_ID, List.of(FILE_ID, FILE_ID_3)));

    assertTrue(port.findByFileId(FILE_ID).isPresent());
    assertTrue(port.findByFileId(FILE_ID_3).isPresent());
  }

  @Test
  void deleteBulk_rejectsBlankInputs() {
    final TutorFileManifestCustodyService service =
        serviceWithPort(new InMemoryCustodiedFileManifestPort(), List.of(PUB_KEY));

    assertThrows(IllegalArgumentException.class, () -> service.deleteBulk(NODE_ID, List.of()));
    assertThrows(IllegalArgumentException.class, () -> service.deleteBulk("", List.of(FILE_ID)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.deleteBulk(NODE_ID, java.util.Arrays.asList(FILE_ID, "")));
  }

  private TutorFileManifestCustodyService serviceWithPort(
      final InMemoryCustodiedFileManifestPort port, final List<String> whitelist) {
    final NodeTopologyProperties topology = new NodeTopologyProperties();
    topology.setTutorAcceptedPublicKeys(whitelist);
    return new TutorFileManifestCustodyService(topology, port, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private TutorFileManifestCustodyService newService(final List<String> whitelist) {
    return serviceWithPort(new InMemoryCustodiedFileManifestPort(), whitelist);
  }

  private StoreFileManifestRequest validRequest() {
    return new StoreFileManifestRequest(
        FILE_ID,
        NODE_ID,
        PUB_KEY,
        "/foo/bar",
        "video.mov",
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

  private StoreFileManifestRequest secondRequest() {
    return new StoreFileManifestRequest(
        FILE_ID_2,
        NODE_ID,
        PUB_KEY,
        "/foo/bar",
        "second.mov",
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

  private CustodiedFileManifest intruderManifest() {
    return new CustodiedFileManifest(
        FILE_ID_3,
        "intruder-node",
        PUB_KEY,
        "/intruder/A",
        "intruder.mov",
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
        null,
        NOW);
  }
}
