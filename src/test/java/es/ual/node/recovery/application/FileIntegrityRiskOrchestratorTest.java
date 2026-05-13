package es.ual.node.recovery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.filesystem.adapters.out.memory.InMemoryFragmentPlacementPort;
import es.ual.node.filesystem.domain.FragmentHealthStatus;
import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.filesystem.ports.out.FileManifestPort;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.recovery.ports.out.FileRecomposePort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests del FileIntegrityRiskOrchestrator.
 *
 * <ul>
 *   <li>riskScore &lt; threshold → no-op.
 *   <li>threshold cruzado + count(OK) ≥ k → recompose total + counter FILE_RECOMPOSED.
 *   <li>threshold cruzado + count(OK) &lt; k → counter FILE_UNRECOVERABLE sin recompose.
 *   <li>recompose excepción → counter FILE_RECOMPOSE_FAILED.
 * </ul>
 */
class FileIntegrityRiskOrchestratorTest {

  private static final String FILE_ID = "11111111-1111-1111-1111-111111111111";
  private static final String CHECKSUM =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String FILE_HASH =
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  private static final String USERNAME = "user-1";
  private static final String ENTRY_ID = "entry-1";
  private static final Instant NOW = Instant.parse("2026-05-04T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final InMemoryFragmentPlacementPort placementPort = new InMemoryFragmentPlacementPort();
  private final RecordingManifestPort manifestPort = new RecordingManifestPort();
  private final RecordingFsEntryPort fsEntryPort = new RecordingFsEntryPort();
  private final RecordingRecomposePort recomposePort = new RecordingRecomposePort();
  private final RecoveryProperties recoveryProperties = new RecoveryProperties();

  private RecoveryObservabilityService observabilityService;
  private FileIntegrityRiskOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    recoveryProperties.setRecomposeThresholdFraction(0.34d);
    recoveryProperties.setRecomposeMinHealthy(0); // 0 → usar k del manifest
    observabilityService = new RecoveryObservabilityService();
    orchestrator =
        new FileIntegrityRiskOrchestrator(
            placementPort,
            manifestPort,
            fsEntryPort,
            recomposePort,
            recoveryProperties,
            CLOCK,
            observabilityService);
  }

  @Test
  void scoreBelowThresholdIsNoOp() {
    placementPort.save(placement("frag-A", FragmentHealthStatus.OK));
    placementPort.save(placement("frag-B", FragmentHealthStatus.OK));
    placementPort.save(placement("frag-C", FragmentHealthStatus.OK));
    manifestPort.put(FILE_ID, manifest(3, 2));
    fsEntryPort.put(FILE_ID, fsEntry());

    final FileIntegrityRiskOrchestrator.CycleSummary summary = orchestrator.runOnce();

    assertEquals(1, summary.evaluated());
    assertEquals(0, summary.recomposed());
    assertEquals(0, summary.unrecoverable());
    assertEquals(0, summary.recomposeFailures());
    assertTrue(recomposePort.uploads.isEmpty());
    assertEquals(0L, observabilityService.snapshot().fileIntegrityRecomposeTotal());
    assertEquals(0L, observabilityService.snapshot().fileIntegrityUnrecoverableTotal());
    assertEquals(0L, observabilityService.snapshot().fileIntegrityRecomposeFailureTotal());
  }

  @Test
  void thresholdCrossedWithSufficientHealthyTriggersRecompose() {
    // RS(3,2) k=2: 2 OK + 1 EN_RIESGO → score=0.5/3=0.167 > 0.16 (custom threshold)
    recoveryProperties.setRecomposeThresholdFraction(0.16d);
    placementPort.save(placement("frag-A", FragmentHealthStatus.OK));
    placementPort.save(placement("frag-B", FragmentHealthStatus.OK));
    placementPort.save(placement("frag-C", FragmentHealthStatus.EN_RIESGO));
    manifestPort.put(FILE_ID, manifest(3, 2));
    fsEntryPort.put(FILE_ID, fsEntry());
    recomposePort.contentForFile.put(FILE_ID, "hola mundo recompose".getBytes());

    final FileIntegrityRiskOrchestrator.CycleSummary summary = orchestrator.runOnce();

    assertEquals(1, summary.recomposed());
    assertEquals(0, summary.unrecoverable());
    assertEquals(0, summary.recomposeFailures());
    assertEquals(1, recomposePort.uploads.size());
    assertEquals(1L, observabilityService.snapshot().fileIntegrityRecomposeTotal());
  }

  @Test
  void thresholdCrossedWithInsufficientHealthyMarksUnrecoverableNoRecompose() {
    placementPort.save(placement("frag-A", FragmentHealthStatus.OK));
    placementPort.save(placement("frag-B", FragmentHealthStatus.PERDIDO));
    placementPort.save(placement("frag-C", FragmentHealthStatus.PERDIDO));
    manifestPort.put(FILE_ID, manifest(3, 2));
    fsEntryPort.put(FILE_ID, fsEntry());

    final FileIntegrityRiskOrchestrator.CycleSummary summary = orchestrator.runOnce();

    assertEquals(0, summary.recomposed());
    assertEquals(1, summary.unrecoverable());
    assertTrue(recomposePort.uploads.isEmpty(), "no se intenta recompose");
    assertEquals(1L, observabilityService.snapshot().fileIntegrityUnrecoverableTotal());
  }

  @Test
  void recomposeFailureIncrementsFailureCounter() {
    recoveryProperties.setRecomposeThresholdFraction(0.16d);
    placementPort.save(placement("frag-A", FragmentHealthStatus.OK));
    placementPort.save(placement("frag-B", FragmentHealthStatus.OK));
    placementPort.save(placement("frag-C", FragmentHealthStatus.EN_RIESGO));
    manifestPort.put(FILE_ID, manifest(3, 2));
    fsEntryPort.put(FILE_ID, fsEntry());
    recomposePort.contentForFile.put(FILE_ID, "bytes".getBytes());
    recomposePort.failOnUpload = true;

    final FileIntegrityRiskOrchestrator.CycleSummary summary = orchestrator.runOnce();

    assertEquals(0, summary.recomposed());
    assertEquals(1, summary.recomposeFailures());
    assertEquals(0, summary.unrecoverable());
    assertEquals(1L, observabilityService.snapshot().fileIntegrityRecomposeFailureTotal());
  }

  @Test
  void manifestMissingSkipsFile() {
    placementPort.save(placement("frag-A", FragmentHealthStatus.PERDIDO));
    placementPort.save(placement("frag-B", FragmentHealthStatus.PERDIDO));
    placementPort.save(placement("frag-C", FragmentHealthStatus.PERDIDO));

    final FileIntegrityRiskOrchestrator.CycleSummary summary = orchestrator.runOnce();

    assertEquals(1, summary.evaluated());
    assertEquals(0, summary.recomposed());
    assertEquals(0, summary.unrecoverable());
    assertEquals(0, summary.recomposeFailures());
  }

  @Test
  void minHealthyOverrideTakesPrecedenceOverK() {
    recoveryProperties.setRecomposeThresholdFraction(0.16d);
    recoveryProperties.setRecomposeMinHealthy(3);
    placementPort.save(placement("frag-A", FragmentHealthStatus.OK));
    placementPort.save(placement("frag-B", FragmentHealthStatus.OK));
    placementPort.save(placement("frag-C", FragmentHealthStatus.EN_RIESGO));
    manifestPort.put(FILE_ID, manifest(3, 2));
    fsEntryPort.put(FILE_ID, fsEntry());

    final FileIntegrityRiskOrchestrator.CycleSummary summary = orchestrator.runOnce();

    assertEquals(1, summary.unrecoverable());
    assertEquals(0, summary.recomposed());
    assertEquals(1L, observabilityService.snapshot().fileIntegrityUnrecoverableTotal());
  }

  // ---------- Helpers ----------

  private FragmentPlacement placement(final String fragmentId, final FragmentHealthStatus status) {
    return new FragmentPlacement(
        FILE_ID,
        fragmentId,
        0,
        0,
        false,
        "node-X",
        "http://node-X:8080",
        "agreement-" + fragmentId,
        CHECKSUM,
        1024L,
        NOW,
        status,
        null,
        0);
  }

  private FileManifest manifest(final int fragmentCount, final int redundancyK) {
    final List<String> fragmentHashes =
        java.util.stream.IntStream.range(0, fragmentCount).mapToObj(i -> CHECKSUM).toList();
    return new FileManifest(
        FILE_ID,
        "/file.txt",
        "file.txt",
        2048L,
        null,
        null,
        FILE_HASH,
        fragmentCount,
        1024L,
        3,
        redundancyK,
        fragmentHashes);
  }

  private FsEntry fsEntry() {
    return new FsEntry(
        ENTRY_ID,
        USERNAME,
        "/file.txt",
        FsEntryType.FILE,
        2048L,
        FILE_HASH,
        FILE_ID,
        1L,
        NOW,
        false,
        true);
  }

  private static final class RecordingManifestPort implements FileManifestPort {
    private final Map<String, FileManifest> store = new HashMap<>();

    void put(final String fileId, final FileManifest manifest) {
      store.put(fileId, manifest);
    }

    @Override
    public void save(final FileManifest manifest, final String username, final String entryId) {
      store.put(manifest.fileId(), manifest);
    }

    @Override
    public Optional<FileManifest> findByFileId(final String fileId) {
      return Optional.ofNullable(store.get(fileId));
    }

    @Override
    public void deleteByFileId(final String fileId) {
      store.remove(fileId);
    }

    @Override
    public java.util.List<String> findAllFileIds() {
      return java.util.List.copyOf(store.keySet());
    }
  }

  private static final class RecordingFsEntryPort implements FsEntryPort {
    private final Map<String, FsEntry> byFileId = new HashMap<>();

    void put(final String fileId, final FsEntry entry) {
      byFileId.put(fileId, entry);
    }

    @Override
    public void save(final FsEntry entry) {
      byFileId.put(entry.fileId(), entry);
    }

    @Override
    public Optional<FsEntry> findByUsernameAndPath(final String username, final String path) {
      return byFileId.values().stream()
          .filter(e -> e.username().equals(username) && e.path().equals(path))
          .findFirst();
    }

    @Override
    public Optional<FsEntry> findByUsernameAndEntryId(final String username, final String entryId) {
      return byFileId.values().stream()
          .filter(e -> e.username().equals(username) && e.entryId().equals(entryId))
          .findFirst();
    }

    @Override
    public List<FsEntry> findByUsername(final String username) {
      return byFileId.values().stream().filter(e -> e.username().equals(username)).toList();
    }

    @Override
    public List<FsEntry> findByUsernameUpdatedAfter(
        final String username, final Instant updatedAfter) {
      return findByUsername(username);
    }

    @Override
    public Optional<FsEntry> findByFileId(final String fileId) {
      return Optional.ofNullable(byFileId.get(fileId));
    }

    @Override
    public List<FsEntry> findByUsernameAndPathSubtree(
        final String username, final String subtreeRoot) {
      return List.of();
    }
  }

  private static final class RecordingRecomposePort implements FileRecomposePort {
    final List<String> uploads = new java.util.ArrayList<>();
    final Map<String, byte[]> contentForFile = new HashMap<>();
    boolean failOnUpload = false;

    @Override
    public byte[] reconstructFileBytes(final String fileId) {
      final byte[] bytes = contentForFile.get(fileId);
      if (bytes == null) {
        throw new IllegalStateException("test stub: content not configured for " + fileId);
      }
      return bytes;
    }

    @Override
    public void reUploadTotal(final FsEntry entry, final byte[] bytes) {
      if (failOnUpload) {
        throw new IllegalStateException("test stub: upload forced to fail");
      }
      uploads.add(entry.fileId());
    }
  }
}
