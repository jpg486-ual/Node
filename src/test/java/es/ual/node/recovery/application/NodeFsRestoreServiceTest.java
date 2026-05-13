package es.ual.node.recovery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFileManifestPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFragmentPlacementPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFsEntryPort;
import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.recovery.application.NodeFsRestoreService.RestoreSummary;
import es.ual.node.recovery.application.RecoveryProperties.RestoreStrategy;
import es.ual.node.recovery.domain.CustodiedFileManifest;
import es.ual.node.recovery.ports.out.RemoteCustodiedManifestListClientPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link NodeFsRestoreService}. */
class NodeFsRestoreServiceTest {

  private static final String NODE_ID = "node-self";
  private static final String PUB_KEY = "pubkey";
  private static final String HASH =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final Instant NOW = Instant.parse("2026-04-27T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void createsFsEntriesFromCustodiedManifestsWithUsernameInPath() {
    final InMemoryFsEntryPort fsPort = new InMemoryFsEntryPort();
    final var client =
        stubClient(
            List.of(
                manifest("11111111-1111-1111-1111-111111111111", "/alice/docs", "report.pdf"),
                manifest("file-2", "/alice/photos/2026", "vacation.jpg")));
    final NodeFsRestoreService service =
        new NodeFsRestoreService(client, fsPort, CLOCK, RestoreStrategy.METADATA_ONLY);

    final RestoreSummary summary = service.restore();

    assertEquals(2, summary.totalManifests());
    assertEquals(2, summary.created());
    assertEquals(0, summary.reused());
    assertEquals(0, summary.skipped());

    final FsEntry restored1 =
        fsPort.findByFileId("11111111-1111-1111-1111-111111111111").orElseThrow();
    assertEquals("alice", restored1.username());
    assertEquals("/docs/report.pdf", restored1.path());
    assertEquals(FsEntryType.FILE, restored1.entryType());
    assertEquals("11111111-1111-1111-1111-111111111111", restored1.fileId());

    final FsEntry restored2 = fsPort.findByFileId("file-2").orElseThrow();
    assertEquals("alice", restored2.username());
    assertEquals("/photos/2026/vacation.jpg", restored2.path());
  }

  @Test
  void preservesExistingFsEntryWhenFileIdAlreadyKnown() {
    final InMemoryFsEntryPort fsPort = new InMemoryFsEntryPort();
    fsPort.save(
        new FsEntry(
            "existing-entry-id",
            "alice",
            "/docs/report.pdf",
            FsEntryType.FILE,
            4096L,
            HASH,
            "11111111-1111-1111-1111-111111111111",
            5L,
            NOW,
            false));
    final var client =
        stubClient(
            List.of(manifest("11111111-1111-1111-1111-111111111111", "/alice/docs", "report.pdf")));
    final NodeFsRestoreService service =
        new NodeFsRestoreService(client, fsPort, CLOCK, RestoreStrategy.METADATA_ONLY);

    final RestoreSummary summary = service.restore();

    assertEquals(1, summary.totalManifests());
    assertEquals(0, summary.created());
    assertEquals(1, summary.reused());

    // Existing entry untouched (version still 5).
    final FsEntry kept = fsPort.findByFileId("11111111-1111-1111-1111-111111111111").orElseThrow();
    assertEquals(5L, kept.version());
    assertEquals("existing-entry-id", kept.entryId());
  }

  @Test
  void skipsManifestsWithoutUsernameSegmentInDirectoryPath() {
    final InMemoryFsEntryPort fsPort = new InMemoryFsEntryPort();
    final var client = stubClient(List.of(manifest("file-orphan", "/", "lost.bin")));
    final NodeFsRestoreService service =
        new NodeFsRestoreService(client, fsPort, CLOCK, RestoreStrategy.METADATA_ONLY);

    final RestoreSummary summary = service.restore();

    assertEquals(1, summary.totalManifests());
    assertEquals(0, summary.created());
    assertEquals(1, summary.skipped());
    assertTrue(fsPort.findByFileId("file-orphan").isEmpty());
  }

  // ---------- Restauración manifest+placements ----------

  @Test
  void restoresFileManifestAndPlacementsFromCustodiedManifest() throws Exception {
    final InMemoryFsEntryPort fsPort = new InMemoryFsEntryPort();
    final InMemoryFileManifestPort manifestPort = new InMemoryFileManifestPort();
    final InMemoryFragmentPlacementPort placementPort = new InMemoryFragmentPlacementPort();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    final List<FragmentPlacement> placements =
        List.of(
            placement(
                "11111111-1111-1111-1111-111111111111",
                "frag-0",
                0,
                false,
                "node-A",
                "http://node-a:8080"),
            placement(
                "11111111-1111-1111-1111-111111111111",
                "frag-1",
                1,
                false,
                "node-B",
                "http://node-b:8080"),
            placement(
                "11111111-1111-1111-1111-111111111111",
                "frag-2",
                2,
                true,
                "node-C",
                "http://node-c:8080"));
    final String placementsJson = mapper.writeValueAsString(placements);

    final CustodiedFileManifest cm =
        manifestWithPlacements(
            "11111111-1111-1111-1111-111111111111", "/alice/docs", "report.pdf", placementsJson);
    final NodeFsRestoreService service =
        new NodeFsRestoreService(
            stubClient(List.of(cm)),
            fsPort,
            manifestPort,
            placementPort,
            mapper,
            CLOCK,
            RestoreStrategy.METADATA_ONLY);

    final RestoreSummary summary = service.restore();

    assertEquals(1, summary.created());
    assertEquals(
        "alice",
        fsPort.findByFileId("11111111-1111-1111-1111-111111111111").orElseThrow().username());

    // Manifest restored
    assertNotNull(manifestPort.findByFileId("11111111-1111-1111-1111-111111111111").orElseThrow());

    // Placements restored
    final List<FragmentPlacement> restored =
        placementPort.findByFileId("11111111-1111-1111-1111-111111111111");
    assertEquals(3, restored.size());
    assertEquals("node-A", restored.get(0).custodianNodeId());
    assertEquals("node-B", restored.get(1).custodianNodeId());
    assertEquals("node-C", restored.get(2).custodianNodeId());
  }

  @Test
  void skipsPlacementsWhenClientPlacementsJsonIsNull() {
    final InMemoryFsEntryPort fsPort = new InMemoryFsEntryPort();
    final InMemoryFileManifestPort manifestPort = new InMemoryFileManifestPort();
    final InMemoryFragmentPlacementPort placementPort = new InMemoryFragmentPlacementPort();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    // Legacy 16-arg constructor of CustodiedFileManifest defaults clientPlacementsJson to null.
    final CustodiedFileManifest legacy =
        manifest("22222222-2222-2222-2222-222222222222", "/alice/docs", "old.pdf");

    final NodeFsRestoreService service =
        new NodeFsRestoreService(
            stubClient(List.of(legacy)),
            fsPort,
            manifestPort,
            placementPort,
            mapper,
            CLOCK,
            RestoreStrategy.METADATA_ONLY);

    final RestoreSummary summary = service.restore();

    assertEquals(1, summary.created());
    // fs_entry creado
    assertEquals(
        "alice",
        fsPort.findByFileId("22222222-2222-2222-2222-222222222222").orElseThrow().username());
    // manifest restaurado
    assertNotNull(manifestPort.findByFileId("22222222-2222-2222-2222-222222222222").orElseThrow());
    // placements vacíos por null clientPlacementsJson
    assertTrue(placementPort.findByFileId("22222222-2222-2222-2222-222222222222").isEmpty());
  }

  // ---------- BYTES_FROM_TUTOR re-uploads, no persiste blob local ----------

  @Test
  void bytesFromTutorReUploadsReconstructedBytesInsteadOfPersistingLocalBlob() throws Exception {
    final InMemoryFsEntryPort fsPort = new InMemoryFsEntryPort();
    final InMemoryFileManifestPort manifestPort = new InMemoryFileManifestPort();
    final InMemoryFragmentPlacementPort placementPort = new InMemoryFragmentPlacementPort();
    final RecordingFileRecomposePort recomposePort = new RecordingFileRecomposePort();
    final RecordingRemoteFileManifestStorePort tutorStorePort =
        new RecordingRemoteFileManifestStorePort();
    final RecordingRemoteOrphanFragmentAckClient ackClient =
        new RecordingRemoteOrphanFragmentAckClient();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final String fileId = "44444444-4444-4444-4444-444444444444";
    final byte[] expectedBytes = "RESTORE_REUPLOAD test payload".getBytes();

    // Single-block placements: 3 fragments
    final List<FragmentPlacement> placements =
        List.of(
            placement(fileId, "frag-0", 0, false, "node-A", "http://a:8080"),
            placement(fileId, "frag-1", 1, false, "node-B", "http://b:8080"),
            placement(fileId, "frag-2", 2, true, "node-C", "http://c:8080"));

    final CustodiedFileManifest cm =
        manifestWithPlacements(fileId, "/alice", "test.bin", mapper.writeValueAsString(placements));

    // Stub reconstruct client returns the expected bytes for the single block.
    final RecordingReconstructClient reconstructClient =
        new RecordingReconstructClient(expectedBytes);

    final NodeFsRestoreService service =
        new NodeFsRestoreService(
            stubClient(List.of(cm)),
            fsPort,
            manifestPort,
            placementPort,
            mapper,
            CLOCK,
            RestoreStrategy.BYTES_FROM_TUTOR,
            reconstructClient,
            recomposePort,
            tutorStorePort,
            ackClient,
            "http://tutor:8080");

    service.restore();

    // Bytes pulled from tutor and re-emitted as standard upload (no local blob storage).
    assertEquals(1, recomposePort.reuploads.size(), "reUploadTotal called exactly once");
    final RecordingFileRecomposePort.Reupload call = recomposePort.reuploads.get(0);
    assertEquals(fsPort.findByFileId(fileId).orElseThrow().entryId(), call.entry.entryId());
    assertEquals(expectedBytes.length, call.bytes.length);
    for (int i = 0; i < expectedBytes.length; i++) {
      assertEquals(expectedBytes[i], call.bytes[i]);
    }
    // Old manifest deleted from tutor.
    assertEquals(1, tutorStorePort.deletes.size());
    assertEquals(fileId, tutorStorePort.deletes.get(0).fileId);
    assertEquals("http://tutor:8080", tutorStorePort.deletes.get(0).tutorBaseUrl);
    // Orphan fragments ACKed at tutor — uno por placement del manifest viejo.
    assertEquals(3, ackClient.acks.size(), "one ACK per orphan fragment");
    assertEquals("frag-0", ackClient.acks.get(0).fragmentId);
    assertEquals("http://tutor:8080", ackClient.acks.get(0).tutorBaseUrl);
    // Reconstruct invoked once per block.
    assertEquals(1, reconstructClient.calls.size());
  }

  @Test
  void bytesFromTutorSkipsReuploadWhenReconstructFails() throws Exception {
    final InMemoryFsEntryPort fsPort = new InMemoryFsEntryPort();
    final InMemoryFileManifestPort manifestPort = new InMemoryFileManifestPort();
    final InMemoryFragmentPlacementPort placementPort = new InMemoryFragmentPlacementPort();
    final RecordingFileRecomposePort recomposePort = new RecordingFileRecomposePort();
    final RecordingRemoteFileManifestStorePort tutorStorePort =
        new RecordingRemoteFileManifestStorePort();
    final RecordingRemoteOrphanFragmentAckClient ackClient =
        new RecordingRemoteOrphanFragmentAckClient();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final String fileId = "55555555-5555-5555-5555-555555555555";

    final List<FragmentPlacement> placements =
        List.of(placement(fileId, "frag-0", 0, false, "node-A", "http://a:8080"));
    final CustodiedFileManifest cm =
        manifestWithPlacements(fileId, "/alice", "fail.bin", mapper.writeValueAsString(placements));

    // Stub that throws — reconstruct fails (tutor unreachable / signature rejected / etc).
    final es.ual.node.recovery.ports.out.RemoteRecoveryReconstructClientPort failingClient =
        (tutorBase, fid, hash, n, k, sym, frags) -> {
          throw new IllegalStateException("simulated tutor unreachable");
        };

    final NodeFsRestoreService service =
        new NodeFsRestoreService(
            stubClient(List.of(cm)),
            fsPort,
            manifestPort,
            placementPort,
            mapper,
            CLOCK,
            RestoreStrategy.BYTES_FROM_TUTOR,
            failingClient,
            recomposePort,
            tutorStorePort,
            ackClient,
            "http://tutor:8080");

    service.restore();

    // fs_entry + manifest + placements restaurados (catalog OK)
    assertTrue(fsPort.findByFileId(fileId).isPresent());
    // Reconstruct falló → no se invocó reupload, no se borró manifest del tutor, no se ACKearon
    // los orphans (no hay confirmación de que los bytes estén redistribuidos).
    assertTrue(recomposePort.reuploads.isEmpty(), "reUploadTotal must not be called");
    assertTrue(tutorStorePort.deletes.isEmpty(), "old manifest delete must not be called");
    assertTrue(ackClient.acks.isEmpty(), "orphan ACK must not be called when reconstruct failed");
  }

  @Test
  void bytesFromTutorWithMultiBlockReUploadsConcatenatedBytes() throws Exception {
    final InMemoryFsEntryPort fsPort = new InMemoryFsEntryPort();
    final InMemoryFileManifestPort manifestPort = new InMemoryFileManifestPort();
    final InMemoryFragmentPlacementPort placementPort = new InMemoryFragmentPlacementPort();
    final RecordingFileRecomposePort recomposePort = new RecordingFileRecomposePort();
    final RecordingRemoteFileManifestStorePort tutorStorePort =
        new RecordingRemoteFileManifestStorePort();
    final RecordingRemoteOrphanFragmentAckClient ackClient =
        new RecordingRemoteOrphanFragmentAckClient();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final String fileId = "66666666-6666-6666-6666-666666666666";

    // 2 blocks × 3 fragments
    final List<FragmentPlacement> placements = new java.util.ArrayList<>();
    for (int b = 0; b < 2; b++) {
      for (int f = 0; f < 3; f++) {
        placements.add(
            new FragmentPlacement(
                fileId,
                "b" + b + "-f" + f,
                b,
                f,
                f == 2,
                "node-X",
                "http://x:8080",
                "agr-" + b + f,
                HASH,
                1024L,
                NOW));
      }
    }
    final CustodiedFileManifest cm =
        manifestWithPlacements(
            fileId, "/alice", "multi.bin", mapper.writeValueAsString(placements));

    // Stub returns different bytes per block call, simulando blocks distintos
    final byte[] block0 = "BLOCK-0-CONTENT".getBytes();
    final byte[] block1 = "BLOCK-1-CONTENT".getBytes();
    final java.util.concurrent.atomic.AtomicInteger callCount =
        new java.util.concurrent.atomic.AtomicInteger();
    final es.ual.node.recovery.ports.out.RemoteRecoveryReconstructClientPort multiBlockClient =
        (tutorBase, fid, hash, n, k, sym, frags) -> {
          final int idx = callCount.getAndIncrement();
          return idx == 0 ? block0 : block1;
        };

    final NodeFsRestoreService service =
        new NodeFsRestoreService(
            stubClient(List.of(cm)),
            fsPort,
            manifestPort,
            placementPort,
            mapper,
            CLOCK,
            RestoreStrategy.BYTES_FROM_TUTOR,
            multiBlockClient,
            recomposePort,
            tutorStorePort,
            ackClient,
            "http://tutor:8080");

    service.restore();

    assertEquals(1, recomposePort.reuploads.size());
    final byte[] reuploaded = recomposePort.reuploads.get(0).bytes;
    final byte[] expected = new byte[block0.length + block1.length];
    System.arraycopy(block0, 0, expected, 0, block0.length);
    System.arraycopy(block1, 0, expected, block0.length, block1.length);
    assertEquals(expected.length, reuploaded.length);
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], reuploaded[i]);
    }
    assertEquals(2, callCount.get(), "one reconstruct call per block");
    assertEquals(1, tutorStorePort.deletes.size(), "old manifest deleted from tutor once");
    // 2 blocks × 3 fragments = 6 ACKs al tutor.
    assertEquals(6, ackClient.acks.size(), "one ACK per orphan fragment across all blocks");
  }

  // Recording stub for RemoteOrphanFragmentAckClientPort: captures ack invocations.
  private static final class RecordingRemoteOrphanFragmentAckClient
      implements es.ual.node.recovery.ports.out.RemoteOrphanFragmentAckClientPort {
    final java.util.List<Ack> acks = new java.util.ArrayList<>();

    @Override
    public void ack(final String fragmentId, final String tutorBaseUrl) {
      acks.add(new Ack(fragmentId, tutorBaseUrl));
    }

    record Ack(String fragmentId, String tutorBaseUrl) {}
  }

  // Recording stub for FileRecomposePort: captures reUploadTotal invocations.
  private static final class RecordingFileRecomposePort
      implements es.ual.node.recovery.ports.out.FileRecomposePort {
    final java.util.List<Reupload> reuploads = new java.util.ArrayList<>();

    @Override
    public byte[] reconstructFileBytes(final String fileId) {
      throw new UnsupportedOperationException("not used by NodeFsRestoreService");
    }

    @Override
    public void reUploadTotal(final FsEntry entry, final byte[] bytes) {
      reuploads.add(new Reupload(entry, bytes.clone()));
    }

    record Reupload(FsEntry entry, byte[] bytes) {}
  }

  // Recording stub for RemoteFileManifestStorePort: only DELETE is exercised by the restore path.
  private static final class RecordingRemoteFileManifestStorePort
      implements es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort {
    final java.util.List<Delete> deletes = new java.util.ArrayList<>();

    @Override
    public void store(
        final es.ual.node.negotiation.domain.FileManifest manifest,
        final java.util.List<FragmentPlacement> placements,
        final String tutorBaseUrl) {
      throw new UnsupportedOperationException("not used by NodeFsRestoreService");
    }

    @Override
    public void delete(final String fileId, final String tutorBaseUrl) {
      deletes.add(new Delete(fileId, tutorBaseUrl));
    }

    @Override
    public void updatePath(
        final String fileId,
        final String newDirectoryPath,
        final String newOriginalFileName,
        final String tutorBaseUrl) {
      throw new UnsupportedOperationException("not used by NodeFsRestoreService");
    }

    @Override
    public void updatePathBulk(
        final java.util.List<BulkUpdateEntry> entries, final String tutorBaseUrl) {
      throw new UnsupportedOperationException("not used by NodeFsRestoreService");
    }

    @Override
    public void deleteBulk(final java.util.List<String> fileIds, final String tutorBaseUrl) {
      throw new UnsupportedOperationException("not used by NodeFsRestoreService");
    }

    @Override
    public void checkTutorReachable(final String tutorBaseUrl) {
      throw new UnsupportedOperationException("not used by NodeFsRestoreService");
    }

    record Delete(String fileId, String tutorBaseUrl) {}
  }

  // Helper recording client that captures call details.
  private static final class RecordingReconstructClient
      implements es.ual.node.recovery.ports.out.RemoteRecoveryReconstructClientPort {
    final java.util.List<RecordedCall> calls = new java.util.ArrayList<>();
    final byte[] returnBytes;

    RecordingReconstructClient(final byte[] returnBytes) {
      this.returnBytes = returnBytes;
    }

    @Override
    public byte[] reconstruct(
        final String tutorBaseUrl,
        final String fileId,
        final String expectedOriginalHash,
        final int redundancyN,
        final int redundancyK,
        final int symbolSize,
        final java.util.List<FragmentReference> fragments) {
      calls.add(new RecordedCall(tutorBaseUrl, fileId, fragments));
      return returnBytes;
    }

    record RecordedCall(
        String tutorBaseUrl, String fileId, java.util.List<FragmentReference> fragments) {}
  }

  @Test
  void restoresMultiBlockFileManifestWhenClientBlocksJsonPresent() throws Exception {
    final InMemoryFsEntryPort fsPort = new InMemoryFsEntryPort();
    final InMemoryFileManifestPort manifestPort = new InMemoryFileManifestPort();
    final InMemoryFragmentPlacementPort placementPort = new InMemoryFragmentPlacementPort();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    // Multi-block placements: 2 blocks × 3 fragments = 6 placements.
    final List<FragmentPlacement> placements = new java.util.ArrayList<>();
    for (int b = 0; b < 2; b++) {
      for (int f = 0; f < 3; f++) {
        placements.add(
            new FragmentPlacement(
                "33333333-3333-3333-3333-333333333333",
                "b" + b + "-f" + f,
                b,
                f,
                f == 2,
                "node-" + ((char) ('A' + f)),
                "http://node-" + ((char) ('a' + f)) + ":8080",
                "agr-b" + b + "-f" + f,
                HASH,
                1024L,
                NOW));
      }
    }

    // Two BlockManifest entries with their fragment hashes.
    final List<es.ual.node.negotiation.domain.BlockManifest> blocks =
        List.of(
            new es.ual.node.negotiation.domain.BlockManifest(
                0, 4096L, HASH, List.of(HASH, HASH, HASH)),
            new es.ual.node.negotiation.domain.BlockManifest(
                1, 4096L, HASH, List.of(HASH, HASH, HASH)));

    final CustodiedFileManifest cm =
        manifestWithBlocks(
            "33333333-3333-3333-3333-333333333333",
            "/alice",
            "big.bin",
            mapper.writeValueAsString(placements),
            mapper.writeValueAsString(blocks));

    final NodeFsRestoreService service =
        new NodeFsRestoreService(
            stubClient(List.of(cm)),
            fsPort,
            manifestPort,
            placementPort,
            mapper,
            CLOCK,
            RestoreStrategy.METADATA_ONLY);

    service.restore();

    final var restoredManifest =
        manifestPort.findByFileId("33333333-3333-3333-3333-333333333333").orElseThrow();
    assertEquals(
        2, restoredManifest.blocks().size(), "multi-block manifest should restore both blocks");
    assertEquals(0, restoredManifest.blocks().get(0).blockIndex());
    assertEquals(1, restoredManifest.blocks().get(1).blockIndex());
  }

  @Test
  void idempotentRestoreOfExistingManifestAndPlacements() throws Exception {
    final InMemoryFsEntryPort fsPort = new InMemoryFsEntryPort();
    final InMemoryFileManifestPort manifestPort = new InMemoryFileManifestPort();
    final InMemoryFragmentPlacementPort placementPort = new InMemoryFragmentPlacementPort();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final List<FragmentPlacement> placements =
        List.of(
            placement(
                "11111111-1111-1111-1111-111111111111",
                "frag-0",
                0,
                false,
                "node-A",
                "http://node-a:8080"));
    final CustodiedFileManifest cm =
        manifestWithPlacements(
            "11111111-1111-1111-1111-111111111111",
            "/alice/docs",
            "report.pdf",
            mapper.writeValueAsString(placements));
    final NodeFsRestoreService service =
        new NodeFsRestoreService(
            stubClient(List.of(cm)),
            fsPort,
            manifestPort,
            placementPort,
            mapper,
            CLOCK,
            RestoreStrategy.METADATA_ONLY);

    service.restore();
    final RestoreSummary summary2 = service.restore();
    // Segunda pasada: fs_entry existe → reused, no se duplica nada.
    assertEquals(1, summary2.totalManifests());
    assertEquals(1, summary2.reused());
    assertEquals(0, summary2.created());
  }

  private FragmentPlacement placement(
      final String fileId,
      final String fragmentId,
      final int fragmentIndex,
      final boolean parity,
      final String custodianNodeId,
      final String custodianBaseUrl) {
    return new FragmentPlacement(
        fileId,
        fragmentId,
        0,
        fragmentIndex,
        parity,
        custodianNodeId,
        custodianBaseUrl,
        "agreement-" + fragmentId,
        HASH,
        1024L,
        NOW);
  }

  private CustodiedFileManifest manifestWithPlacements(
      final String fileId,
      final String directoryPath,
      final String fileName,
      final String placementsJson) {
    return new CustodiedFileManifest(
        fileId,
        NODE_ID,
        PUB_KEY,
        directoryPath,
        fileName,
        HASH,
        4096L,
        null,
        null,
        4,
        1024L,
        6,
        4,
        List.of(HASH, HASH, HASH, HASH),
        placementsJson,
        null,
        NOW);
  }

  private CustodiedFileManifest manifestWithBlocks(
      final String fileId,
      final String directoryPath,
      final String fileName,
      final String placementsJson,
      final String blocksJson) {
    return new CustodiedFileManifest(
        fileId,
        NODE_ID,
        PUB_KEY,
        directoryPath,
        fileName,
        HASH,
        8192L,
        null,
        null,
        6,
        1024L,
        3,
        2,
        List.of(HASH, HASH, HASH, HASH, HASH, HASH),
        placementsJson,
        blocksJson,
        NOW);
  }

  private RemoteCustodiedManifestListClientPort stubClient(
      final List<CustodiedFileManifest> manifests) {
    return () -> manifests;
  }

  private CustodiedFileManifest manifest(
      final String fileId, final String directoryPath, final String fileName) {
    return new CustodiedFileManifest(
        fileId,
        NODE_ID,
        PUB_KEY,
        directoryPath,
        fileName,
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
