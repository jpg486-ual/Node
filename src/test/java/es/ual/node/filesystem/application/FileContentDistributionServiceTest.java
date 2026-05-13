package es.ual.node.filesystem.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.filesystem.adapters.out.memory.InMemoryFileManifestPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFragmentPlacementPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryRemoteFileManifestStoreAdapter;
import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort;
import es.ual.node.filesystem.ports.out.RemoteFragmentDistributionClientPort;
import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsCodecAdapter;
import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsIntegrityVerifier;
import es.ual.node.reedsolomon.domain.RsScheme;
import es.ual.node.userregistration.adapters.out.memory.InMemoryUserAccountPort;
import es.ual.node.userregistration.adapters.out.memory.InMemoryUserQuotaPort;
import es.ual.node.userregistration.domain.UserAccount;
import es.ual.node.userregistration.domain.UserRole;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FileContentDistributionService}. */
class FileContentDistributionServiceTest {

  private static final String USERNAME = "alice";
  private static final long BYTES_PER_MB = 1024L * 1024L;
  private static final long MAX_BYTES = 10L * BYTES_PER_MB;
  private static final List<String> CUSTODIANS =
      List.of("http://node1:8080", "http://node2:8080", "http://node3:8080");

  private InMemoryUserAccountPort accountPort;
  private InMemoryUserQuotaPort quotaPort;
  private InMemoryFileManifestPort manifestPort;
  private InMemoryFragmentPlacementPort placementPort;
  private RecordingRemoteClient remoteClient;
  private InMemoryRsCodecAdapter rsCodec;
  private FileContentDistributionService sut;

  @BeforeEach
  void setUp() {
    accountPort = new InMemoryUserAccountPort();
    accountPort.save(new UserAccount(USERNAME, "hash", 10, UserRole.END_USER, Instant.EPOCH));
    quotaPort = new InMemoryUserQuotaPort(accountPort);
    manifestPort = new InMemoryFileManifestPort();
    placementPort = new InMemoryFragmentPlacementPort();
    remoteClient = new RecordingRemoteClient();
    rsCodec = new InMemoryRsCodecAdapter();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-01T20:00:00Z"), ZoneId.of("UTC"));
    final RsScheme scheme = new RsScheme(3, 2, 16);
    sut =
        new FileContentDistributionService(
            rsCodec,
            rsCodec,
            new InMemoryRsIntegrityVerifier(),
            manifestPort,
            placementPort,
            remoteClient,
            quotaPort,
            clock,
            scheme,
            MAX_BYTES,
            CUSTODIANS);
  }

  @Test
  void rejectsNullDependencies() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FileContentDistributionService(
                null,
                rsCodec,
                new InMemoryRsIntegrityVerifier(),
                manifestPort,
                placementPort,
                remoteClient,
                quotaPort,
                Clock.systemUTC(),
                new RsScheme(3, 2, 16),
                MAX_BYTES,
                CUSTODIANS));
  }

  @Test
  void distributeUploadStoresFragmentsAndPersistsManifest() {
    final byte[] payload = "demo content for distribution".getBytes();
    final FsEntry entry = activeEntryFor(payload);

    final FileManifest manifest = sut.distributeUpload(USERNAME, entry, payload);

    assertEquals(payload.length, manifest.originalSizeBytes());
    assertEquals(3, manifest.redundancyN());
    assertEquals(2, manifest.redundancyK());
    assertEquals(3, remoteClient.stores.size());
    assertEquals(3, placementPort.findByFileId(manifest.fileId()).size());
    assertTrue(manifestPort.findByFileId(manifest.fileId()).isPresent());
    final long expectedCharged = ((long) payload.length * 3) / 2;
    assertEquals(expectedCharged, quotaPort.usedBytes(USERNAME));
  }

  @Test
  void rejectsUploadOverMaxSize() {
    final byte[] tooBig = new byte[(int) MAX_BYTES + 1];
    assertThrows(
        ContentTooLargeException.class,
        () -> sut.distributeUpload(USERNAME, activeEntry(), tooBig));
    assertEquals(0L, quotaPort.usedBytes(USERNAME));
  }

  @Test
  void rejectsUploadWhenInsufficientCustodians() {
    final RsScheme scheme = new RsScheme(3, 2, 16);
    final FileContentDistributionService onlyOneCustodian =
        new FileContentDistributionService(
            rsCodec,
            rsCodec,
            new InMemoryRsIntegrityVerifier(),
            manifestPort,
            placementPort,
            remoteClient,
            quotaPort,
            Clock.systemUTC(),
            scheme,
            MAX_BYTES,
            List.of("http://only-one:8080"));

    assertThrows(
        InsufficientCustodiansException.class,
        () -> onlyOneCustodian.distributeUpload(USERNAME, activeEntry(), "x".getBytes()));
    assertEquals(0L, quotaPort.usedBytes(USERNAME));
  }

  @Test
  void rejectsUploadWhenQuotaExceeded() {
    // First upload: 4 MB → charged 4 × 3/2 = 6 MB. Fits in the 10 MB quota.
    final byte[] firstPayload = new byte[(int) (4 * BYTES_PER_MB)];
    sut.distributeUpload(USERNAME, activeEntryFor(firstPayload), firstPayload);
    // Second upload: 3 MB → would charge 3 × 3/2 = 4.5 MB. Available = 10 - 6 = 4 MB. Exceeds.
    final byte[] secondPayload = new byte[(int) (3 * BYTES_PER_MB)];
    assertThrows(
        QuotaExceededException.class,
        () -> sut.distributeUpload(USERNAME, activeEntryFor(secondPayload), secondPayload));
  }

  @Test
  void releasesQuotaWhenCustodianRefuses() {
    remoteClient.failOnNthStore = 2;
    final byte[] payload = "demo".getBytes();

    assertThrows(
        IllegalStateException.class,
        () -> sut.distributeUpload(USERNAME, activeEntryFor(payload), payload));
    assertEquals(0L, quotaPort.usedBytes(USERNAME));
  }

  @Test
  void reconstructDownloadFetchesUntilKAndDecodes() {
    final byte[] original = "reconstruction test payload".getBytes();
    final FileManifest manifest =
        sut.distributeUpload(USERNAME, activeEntryFor(original), original);

    final byte[] reconstructed = sut.reconstructDownload(manifest.fileId());
    assertArrayEquals(original, reconstructed);
  }

  @Test
  void reconstructStillSucceedsWithOneCustodianDown() {
    final byte[] original = "robustness test".getBytes();
    final FileManifest manifest =
        sut.distributeUpload(USERNAME, activeEntryFor(original), original);
    remoteClient.unreachableBaseUrls.add("http://node3:8080");

    final byte[] reconstructed = sut.reconstructDownload(manifest.fileId());
    assertArrayEquals(original, reconstructed);
  }

  @Test
  void reconstructFailsWhenLessThanKAvailable() {
    final byte[] original = "reconstruct fails".getBytes();
    final FileManifest manifest =
        sut.distributeUpload(USERNAME, activeEntryFor(original), original);
    remoteClient.unreachableBaseUrls.addAll(CUSTODIANS);

    assertThrows(
        InsufficientCustodiansException.class, () -> sut.reconstructDownload(manifest.fileId()));
  }

  @Test
  void reconstructThrowsWhenManifestUnknown() {
    assertThrows(NoSuchElementException.class, () -> sut.reconstructDownload("unknown-id"));
  }

  @Test
  void reuploadGeneratesNewFileIdAndCleansPreviousPlacements() {
    // El re-upload del mismo entryId no debe acumular placements ni manifests bajo el fileId
    // previo. Cada upload genera fileId nuevo y limpia el viejo localmente. Los fragments
    // huérfanos en peers los libera el flujo de probes (releasableFragmentIds[]) — se valida en
    // E2E, no aquí.
    final byte[] firstPayload = "first content".getBytes();
    final FsEntry firstEntry = activeEntryFor(firstPayload);
    final FileManifest firstManifest = sut.distributeUpload(USERNAME, firstEntry, firstPayload);
    final String firstFileId = firstManifest.fileId();
    assertEquals(3, placementPort.findByFileId(firstFileId).size());
    assertTrue(manifestPort.findByFileId(firstFileId).isPresent());

    // Re-upload sobre el MISMO entryId, distinto contenido. El entry viene del cliente con su
    // checksum pre-calculado; en re-uploads reales, el entry trae también el fileId del primer
    // upload — simulamos esa semántica con secondEntry.fileId() = firstFileId.
    final byte[] secondPayload = "second content overwriting first".getBytes();
    final FsEntry secondEntry =
        new FsEntry(
            firstEntry.entryId(),
            USERNAME,
            firstEntry.path(),
            FsEntryType.FILE,
            secondPayload.length,
            sha256Hex(secondPayload),
            firstFileId,
            2L,
            Instant.parse("2026-05-01T20:30:00Z"),
            false);
    final FileManifest secondManifest = sut.distributeUpload(USERNAME, secondEntry, secondPayload);
    final String secondFileId = secondManifest.fileId();

    // Invariante 1: fileId nuevo distinto del previo.
    assertNotEquals(firstFileId, secondFileId);
    // Invariante 2: placements del fileId previo borrados (cero filas).
    assertEquals(0, placementPort.findByFileId(firstFileId).size());
    // Invariante 3: manifest del fileId previo borrado.
    assertTrue(manifestPort.findByFileId(firstFileId).isEmpty());
    // Invariante 4: placements del nuevo fileId persisten exactamente n=3 filas.
    assertEquals(3, placementPort.findByFileId(secondFileId).size());
    assertTrue(manifestPort.findByFileId(secondFileId).isPresent());
  }

  @Test
  void reconstructFailsFastOnDuplicatePlacements() {
    // Si el placement table tiene dos filas para (blockIndex, fragmentIndex), reconstruct
    // debe lanzar InconsistentFragmentPlacementException ANTES de pegar a ningún peer.
    final byte[] payload = "preflight test payload".getBytes();
    final FileManifest manifest = sut.distributeUpload(USERNAME, activeEntryFor(payload), payload);
    final String fileId = manifest.fileId();
    assertEquals(3, placementPort.findByFileId(fileId).size());

    // Inject duplicate row for fragment_index=0 (mismo cell que un placement existente).
    final FragmentPlacement first = placementPort.findByFileId(fileId).get(0);
    final FragmentPlacement duplicate =
        new FragmentPlacement(
            first.fileId(),
            "rs-fragment-fake-" + java.util.UUID.randomUUID(),
            first.blockIndex(),
            first.fragmentIndex(),
            first.parity(),
            first.custodianNodeId(),
            first.custodianBaseUrl(),
            first.agreementId(),
            first.fragmentChecksum(),
            999L,
            Instant.parse("2026-05-01T20:30:00Z"));
    placementPort.save(duplicate);
    assertEquals(4, placementPort.findByFileId(fileId).size());

    final InconsistentFragmentPlacementException ex =
        assertThrows(
            InconsistentFragmentPlacementException.class, () -> sut.reconstructDownload(fileId));
    assertEquals(fileId, ex.fileId());
    assertEquals(first.blockIndex(), ex.blockIndex());
    assertEquals(first.fragmentIndex(), ex.fragmentIndex());
    assertEquals(2, ex.count());
  }

  @Test
  void validateReconstructableHappyPathDoesNotThrow() {
    final byte[] payload = "validate ok".getBytes();
    final FileManifest manifest = sut.distributeUpload(USERNAME, activeEntryFor(payload), payload);
    sut.validateReconstructable(manifest.fileId()); // does not throw
  }

  @Test
  void validateReconstructableThrowsWhenManifestMissing() {
    assertThrows(NoSuchElementException.class, () -> sut.validateReconstructable("ghost-id"));
  }

  @Test
  void releaseQuotaForFileFreesBytesAndRemovesArtifacts() {
    final byte[] payload = "release test".getBytes();
    final FileManifest manifest = sut.distributeUpload(USERNAME, activeEntryFor(payload), payload);
    final long charged = quotaPort.usedBytes(USERNAME);
    assertTrue(charged > 0);

    sut.releaseQuotaForFile(USERNAME, manifest.fileId());

    assertEquals(0L, quotaPort.usedBytes(USERNAME));
    assertTrue(manifestPort.findByFileId(manifest.fileId()).isEmpty());
    assertTrue(placementPort.findByFileId(manifest.fileId()).isEmpty());
  }

  // ---------- streaming + RS por bloques ----------

  @Test
  void streamingUploadWithMultipleBlocksProducesMultiBlockManifest() {
    final int blockSize = 8;
    final FileContentDistributionService blockSut = orchestratorWithBlockSize(blockSize);
    final byte[] payload = "ABCDEFGHIJKLMNOPQRSTUVWX".getBytes(); // 24 bytes => 3 blocks of 8

    final FileManifest manifest =
        blockSut.distributeUploadStreaming(
            USERNAME,
            activeEntryFor(payload),
            new java.io.ByteArrayInputStream(payload),
            payload.length);

    assertEquals(payload.length, manifest.originalSizeBytes());
    assertEquals(3, manifest.blocks().size(), "24 bytes / 8 = 3 blocks");
    assertEquals(0, manifest.blocks().get(0).blockIndex());
    assertEquals(8L, manifest.blocks().get(0).blockSizeBytes());
    assertEquals(8L, manifest.blocks().get(2).blockSizeBytes());
    // 3 blocks × n=3 fragments = 9 placements
    assertEquals(9, placementPort.findByFileId(manifest.fileId()).size());
    // 3 blocks × n=3 stores = 9 store calls
    assertEquals(9, remoteClient.stores.size());
  }

  @Test
  void streamingDownloadReconstructsMultiBlockFile() {
    final int blockSize = 8;
    final FileContentDistributionService blockSut = orchestratorWithBlockSize(blockSize);
    final byte[] payload = "0123456789abcdefABCDEF".getBytes(); // 22 bytes => 3 blocks (8+8+6)

    final FileManifest manifest =
        blockSut.distributeUploadStreaming(
            USERNAME,
            activeEntryFor(payload),
            new java.io.ByteArrayInputStream(payload),
            payload.length);

    final byte[] reconstructed = blockSut.reconstructDownload(manifest.fileId());
    assertArrayEquals(payload, reconstructed);
  }

  @Test
  void streamingDownloadStillSucceedsWithOneCustodianDownAcrossBlocks() {
    final int blockSize = 8;
    final FileContentDistributionService blockSut = orchestratorWithBlockSize(blockSize);
    final byte[] payload = "abcdefghIJKLMNOPqrstuvwx".getBytes(); // 24 bytes => 3 blocks

    final FileManifest manifest =
        blockSut.distributeUploadStreaming(
            USERNAME,
            activeEntryFor(payload),
            new java.io.ByteArrayInputStream(payload),
            payload.length);
    remoteClient.unreachableBaseUrls.add("http://node3:8080");

    final byte[] reconstructed = blockSut.reconstructDownload(manifest.fileId());
    assertArrayEquals(payload, reconstructed);
  }

  @Test
  void streamingUploadRejectsContentLengthMismatch() {
    final byte[] payload = "ABCDEFGH".getBytes();
    // Declared 100 bytes but the stream only carries 8: should fail with premature EOF.
    assertThrows(
        IllegalStateException.class,
        () ->
            sut.distributeUploadStreaming(
                USERNAME,
                activeEntryFor(payload),
                new java.io.ByteArrayInputStream(payload),
                100L));
    assertEquals(0L, quotaPort.usedBytes(USERNAME), "failed upload must release reservation");
  }

  @Test
  void streamingDownloadWritesDirectlyToCallerOutputStream() {
    final int blockSize = 8;
    final FileContentDistributionService blockSut = orchestratorWithBlockSize(blockSize);
    final byte[] payload = "0123456789abcdefABCDEF".getBytes();

    final FileManifest manifest =
        blockSut.distributeUploadStreaming(
            USERNAME,
            activeEntryFor(payload),
            new java.io.ByteArrayInputStream(payload),
            payload.length);

    final java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
    blockSut.reconstructDownloadStreaming(manifest.fileId(), sink);

    assertArrayEquals(payload, sink.toByteArray());
  }

  @Test
  void streamingDownloadRejectsNullOutput() {
    assertThrows(
        IllegalArgumentException.class, () -> sut.reconstructDownloadStreaming("any-id", null));
  }

  @Test
  void streamingUploadRejectsChecksumMismatch() {
    final byte[] payload = "honest payload".getBytes();
    // Legacy activeEntry() carries a checksum that does NOT match the payload.
    assertThrows(
        FsContentConflictException.class,
        () ->
            sut.distributeUploadStreaming(
                USERNAME,
                activeEntry(),
                new java.io.ByteArrayInputStream(payload),
                payload.length));
    assertEquals(0L, quotaPort.usedBytes(USERNAME), "failed upload must release reservation");
  }

  private FileContentDistributionService orchestratorWithBlockSize(final int blockSize) {
    return new FileContentDistributionService(
        rsCodec,
        rsCodec,
        new InMemoryRsIntegrityVerifier(),
        manifestPort,
        placementPort,
        remoteClient,
        quotaPort,
        Clock.fixed(Instant.parse("2026-05-01T20:00:00Z"), ZoneId.of("UTC")),
        new RsScheme(3, 2, 16),
        MAX_BYTES,
        blockSize,
        CUSTODIANS);
  }

  @Test
  void releaseQuotaForFileIgnoresUnknownFile() {
    sut.releaseQuotaForFile(USERNAME, "unknown");
  }

  // ---------- tutor manifest replication ----------

  @Test
  void distributeUploadReplicatesManifestToTutorWhenWired() {
    final InMemoryRemoteFileManifestStoreAdapter tutorStore =
        new InMemoryRemoteFileManifestStoreAdapter();
    final FileContentDistributionService orchestrator =
        orchestratorWithTutor(tutorStore, "http://tutor:8080");
    final byte[] payload = "demo content for replication".getBytes();
    final FsEntry entry = activeEntryFor(payload);

    final FileManifest manifest = orchestrator.distributeUpload(USERNAME, entry, payload);

    assertEquals(1, tutorStore.recordCount(), "manifest must be replicated to tutor");
    final InMemoryRemoteFileManifestStoreAdapter.ReplicationRecord record =
        tutorStore.findRecord("http://tutor:8080", manifest.fileId());
    assertEquals(manifest.fileId(), record.manifest().fileId());
    assertEquals(3, record.placements().size(), "all n=3 placements travel embedded");
  }

  @Test
  void distributeUploadAbortsBeforeLocalPersistenceWhenTutorReplicationFails() {
    final RemoteFileManifestStorePort failingStore =
        new RemoteFileManifestStorePort() {
          @Override
          public void store(
              final FileManifest manifest,
              final List<es.ual.node.filesystem.domain.FragmentPlacement> placements,
              final String tutorBaseUrl) {
            throw new IllegalStateException("tutor unreachable");
          }

          @Override
          public void updatePath(
              final String fileId,
              final String newDirectoryPath,
              final String newOriginalFileName,
              final String tutorBaseUrl) {
            // unused in this test
          }

          @Override
          public void updatePathBulk(
              final List<BulkUpdateEntry> entries, final String tutorBaseUrl) {
            // unused in this test
          }

          @Override
          public void deleteBulk(final List<String> fileIds, final String tutorBaseUrl) {
            // unused in this test
          }

          @Override
          public void checkTutorReachable(final String tutorBaseUrl) {
            // unused in this test — happy preflight
          }

          @Override
          public void delete(final String fileId, final String tutorBaseUrl) {
            // unused in this test
          }
        };
    final FileContentDistributionService orchestrator =
        orchestratorWithTutor(failingStore, "http://tutor:8080");
    final byte[] payload = "demo content for abort".getBytes();
    final FsEntry entry = activeEntryFor(payload);

    assertThrows(
        TutorManifestReplicationException.class,
        () -> orchestrator.distributeUpload(USERNAME, entry, payload));

    // Local state must be untouched: no manifest, no placements, quota released.
    assertEquals(0, manifestPort.size(), "no manifest persisted on replication failure");
    assertEquals(0, placementPort.size(), "no placement persisted on replication failure");
    assertEquals(0L, quotaPort.usedBytes(USERNAME), "quota released after failed replication");
  }

  @Test
  void distributeUploadSkipsReplicationWhenTutorBaseUrlIsBlank() {
    final InMemoryRemoteFileManifestStoreAdapter tutorStore =
        new InMemoryRemoteFileManifestStoreAdapter();
    // tutorBaseUrl blank → port wired but skipped (legacy / dev mode).
    final FileContentDistributionService orchestrator = orchestratorWithTutor(tutorStore, "");
    final byte[] payload = "demo content no tutor".getBytes();
    final FsEntry entry = activeEntryFor(payload);

    orchestrator.distributeUpload(USERNAME, entry, payload);

    assertEquals(0, tutorStore.recordCount(), "no replication when tutor URL blank");
    assertEquals(1, manifestPort.size(), "manifest still persisted locally");
  }

  // ---------- preflight tutor health check antes del upload ----------

  @Test
  void distributeUploadAbortsBeforeDistributingFragmentsWhenTutorHealthCheckFails() {
    final InMemoryRemoteFileManifestStoreAdapter tutorStore =
        new InMemoryRemoteFileManifestStoreAdapter();
    tutorStore.simulateHealthCheckFailure(true);
    final FileContentDistributionService orchestrator =
        orchestratorWithTutor(tutorStore, "http://tutor:8080");
    final byte[] payload = "preflight-fail-payload".getBytes();
    final FsEntry entry = activeEntryFor(payload);

    assertThrows(
        TutorManifestReplicationException.class,
        () -> orchestrator.distributeUpload(USERNAME, entry, payload));

    // Health check ran exactly once.
    assertEquals(1, tutorStore.healthCheckInvocationCount());
    // Crítico: cero fragments distribuidos a peers cuando preflight falla.
    assertEquals(
        0,
        remoteClient.stores.size(),
        "no fragment must reach peers when tutor health check fails");
    // Y nada de manifest local + quota liberada.
    assertEquals(0, manifestPort.size());
    assertEquals(0, placementPort.size());
    assertEquals(0L, quotaPort.usedBytes(USERNAME));
    // Y por supuesto tampoco llamó al store del tutor.
    assertEquals(0, tutorStore.recordCount());
  }

  @Test
  void distributeUploadInvokesHealthCheckBeforeDistributionOnHappyPath() {
    final InMemoryRemoteFileManifestStoreAdapter tutorStore =
        new InMemoryRemoteFileManifestStoreAdapter();
    final FileContentDistributionService orchestrator =
        orchestratorWithTutor(tutorStore, "http://tutor:8080");
    final byte[] payload = "preflight-happy-payload".getBytes();

    orchestrator.distributeUpload(USERNAME, activeEntryFor(payload), payload);

    // Health check + replication both happened (1 health + 1 store invocation).
    assertEquals(1, tutorStore.healthCheckInvocationCount());
    assertEquals(1, tutorStore.recordCount());
    // Fragments did reach peers on happy path.
    assertEquals(3, remoteClient.stores.size());
  }

  private FileContentDistributionService orchestratorWithTutor(
      final RemoteFileManifestStorePort tutorStore, final String tutorBaseUrl) {
    return new FileContentDistributionService(
        rsCodec,
        rsCodec,
        new InMemoryRsIntegrityVerifier(),
        manifestPort,
        placementPort,
        remoteClient,
        quotaPort,
        Clock.fixed(Instant.parse("2026-05-01T20:00:00Z"), ZoneId.of("UTC")),
        new RsScheme(3, 2, 16),
        MAX_BYTES,
        Integer.MAX_VALUE,
        CUSTODIANS,
        tutorStore,
        tutorBaseUrl);
  }

  private FsEntry activeEntry() {
    // Legacy helper kept for tests that don't actually invoke distributeUpload (constructor
    // validation, etc). The hardcoded checksum is deliberately not coherent with any payload.
    return new FsEntry(
        "entry-1",
        USERNAME,
        "/docs/demo.txt",
        FsEntryType.FILE,
        100L,
        sha256Hex(new byte[] {1, 2, 3}),
        "11111111-1111-1111-1111-111111111111",
        1L,
        Instant.parse("2026-05-01T20:00:00Z"),
        false);
  }

  /**
   * Entry cuyo {@code checksum} coincide con el SHA-256 del payload, el orchestrator valida los
   * bytes streameados contra {@code entry.checksum()}.
   */
  private FsEntry activeEntryFor(final byte[] content) {
    return new FsEntry(
        "entry-1",
        USERNAME,
        "/docs/demo.txt",
        FsEntryType.FILE,
        content.length,
        sha256Hex(content),
        "11111111-1111-1111-1111-111111111111",
        1L,
        Instant.parse("2026-05-01T20:00:00Z"),
        false);
  }

  private static String sha256Hex(final byte[] payload) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(payload));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  /**
   * In-memory fake of the remote HTTP client. Stores fragments under {@code
   * <baseUrl>::<fragmentId>} and serves fetches back unless the baseUrl is marked unreachable.
   */
  private static final class RecordingRemoteClient implements RemoteFragmentDistributionClientPort {
    private final Map<String, byte[]> stores = new HashMap<>();
    private final java.util.Set<String> unreachableBaseUrls = new java.util.HashSet<>();
    private int failOnNthStore = -1;
    private int storeCount = 0;

    @Override
    public void storeFragment(
        final String custodianBaseUrl,
        final String fragmentId,
        final String agreementId,
        final byte[] payload,
        final String checksumAlgorithm,
        final String checksumHex,
        final Long custodySeconds) {
      storeCount++;
      if (failOnNthStore == storeCount) {
        throw new IllegalStateException("simulated custody store failure");
      }
      stores.put(custodianBaseUrl + "::" + fragmentId, payload.clone());
    }

    @Override
    public byte[] fetchFragment(final String custodianBaseUrl, final String fragmentId) {
      if (unreachableBaseUrls.contains(custodianBaseUrl)) {
        throw new IllegalStateException("custodian unreachable: " + custodianBaseUrl);
      }
      final byte[] payload = stores.get(custodianBaseUrl + "::" + fragmentId);
      if (payload == null) {
        throw new IllegalStateException("fragment not stored: " + fragmentId);
      }
      return payload.clone();
    }
  }
}
