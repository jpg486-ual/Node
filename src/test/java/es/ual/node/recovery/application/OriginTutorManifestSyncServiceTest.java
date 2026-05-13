package es.ual.node.recovery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFileManifestPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryRemoteFileManifestStoreAdapter;
import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.recovery.ports.out.RemoteTutorManifestInventoryClientPort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OriginTutorManifestSyncService}. El supervisado detecta silencio del tutor y
 * dispara endpoint inverso re-emitiendo manifests faltantes.
 */
class OriginTutorManifestSyncServiceTest {

  private static final String TUTOR_BASE_URL = "http://tutor:8080";
  private static final String FILE_1 = "00000000-0000-0000-0000-000000000001";
  private static final String FILE_2 = "00000000-0000-0000-0000-000000000002";

  @Test
  void runOnce_reEmitsManifestsAbsentInTutorInventory() {
    final InMemoryFileManifestPort localPort = new InMemoryFileManifestPort();
    final FileManifest m1 = manifest(FILE_1);
    final FileManifest m2 = manifest(FILE_2);
    localPort.save(m1, "alice", "entry-1");
    localPort.save(m2, "alice", "entry-2");

    final InMemoryRemoteFileManifestStoreAdapter store =
        new InMemoryRemoteFileManifestStoreAdapter();
    final RemoteTutorManifestInventoryClientPort inventoryClient = url -> List.of(FILE_1);

    final OriginTutorManifestSyncService service =
        new OriginTutorManifestSyncService(
            localPort,
            inventoryClient,
            store,
            topology(TUTOR_BASE_URL),
            fileId -> List.of(samplePlacement(fileId)));

    final OriginTutorManifestSyncService.CycleSummary summary = service.runOnce();

    assertThat(summary.tutorSilent()).isFalse();
    assertEquals(1, summary.reEmittedManifests());
    // El re-emit fue para FILE_2 (faltante en tutor).
    assertThat(store.findRecord(TUTOR_BASE_URL, FILE_2)).isNotNull();
    assertThat(store.findRecord(TUTOR_BASE_URL, FILE_1)).isNull();
  }

  @Test
  void runOnce_skipsAllWhenInventoryMatchesLocal() {
    final InMemoryFileManifestPort localPort = new InMemoryFileManifestPort();
    localPort.save(manifest(FILE_1), "alice", "entry-1");
    localPort.save(manifest(FILE_2), "alice", "entry-2");

    final InMemoryRemoteFileManifestStoreAdapter store =
        new InMemoryRemoteFileManifestStoreAdapter();
    final RemoteTutorManifestInventoryClientPort inventoryClient = url -> List.of(FILE_1, FILE_2);

    final OriginTutorManifestSyncService service =
        new OriginTutorManifestSyncService(
            localPort,
            inventoryClient,
            store,
            topology(TUTOR_BASE_URL),
            fileId -> List.of(samplePlacement(fileId)));

    final OriginTutorManifestSyncService.CycleSummary summary = service.runOnce();

    assertThat(summary.tutorSilent()).isFalse();
    assertEquals(0, summary.reEmittedManifests());
    assertThat(store.recordCount()).isZero();
  }

  @Test
  void runOnce_marksTutorSilentWhenInventoryFails() {
    final InMemoryFileManifestPort localPort = new InMemoryFileManifestPort();
    localPort.save(manifest(FILE_1), "alice", "entry-1");

    final InMemoryRemoteFileManifestStoreAdapter store =
        new InMemoryRemoteFileManifestStoreAdapter();
    final RemoteTutorManifestInventoryClientPort failingClient =
        url -> {
          throw new IllegalStateException("tutor unreachable");
        };

    final OriginTutorManifestSyncService service =
        new OriginTutorManifestSyncService(
            localPort,
            failingClient,
            store,
            topology(TUTOR_BASE_URL),
            fileId -> List.of(samplePlacement(fileId)));

    final OriginTutorManifestSyncService.CycleSummary summary = service.runOnce();

    assertThat(summary.tutorSilent()).isTrue();
    assertEquals(0, summary.reEmittedManifests());
    assertThat(store.recordCount()).isZero();
  }

  @Test
  void runOnce_isNoopWhenTutorBaseUrlNotConfigured() {
    final InMemoryFileManifestPort localPort = new InMemoryFileManifestPort();
    localPort.save(manifest(FILE_1), "alice", "entry-1");

    final InMemoryRemoteFileManifestStoreAdapter store =
        new InMemoryRemoteFileManifestStoreAdapter();
    final RemoteTutorManifestInventoryClientPort inventoryClient = url -> List.of();

    final OriginTutorManifestSyncService service =
        new OriginTutorManifestSyncService(
            localPort,
            inventoryClient,
            store,
            new NodeTopologyProperties(),
            fileId -> List.of(samplePlacement(fileId)));

    final OriginTutorManifestSyncService.CycleSummary summary = service.runOnce();

    assertEquals(0, summary.reEmittedManifests());
    assertThat(summary.tutorSilent()).isFalse();
  }

  @Test
  void runOnce_continuesIterationWhenSingleStoreCallFails() {
    final InMemoryFileManifestPort localPort = new InMemoryFileManifestPort();
    localPort.save(manifest(FILE_1), "alice", "entry-1");
    localPort.save(manifest(FILE_2), "alice", "entry-2");

    // Tutor tiene 0 manifests; ambos deben re-emitirse. Forzamos que el primer store falle.
    final FailingFirstStore failingStore = new FailingFirstStore();
    final RemoteTutorManifestInventoryClientPort inventoryClient = url -> List.of();

    final OriginTutorManifestSyncService service =
        new OriginTutorManifestSyncService(
            localPort,
            inventoryClient,
            failingStore,
            topology(TUTOR_BASE_URL),
            fileId -> List.of(samplePlacement(fileId)));

    final OriginTutorManifestSyncService.CycleSummary summary = service.runOnce();

    // Uno OK + uno con error.
    assertEquals(1, summary.errors());
    assertEquals(1, summary.reEmittedManifests());
  }

  private NodeTopologyProperties topology(final String tutorBaseUrl) {
    final NodeTopologyProperties topology = new NodeTopologyProperties();
    topology.setTutorBaseUrl(tutorBaseUrl);
    return topology;
  }

  private FileManifest manifest(final String fileId) {
    return new FileManifest(
        fileId,
        "/alice/docs",
        "doc.bin",
        4096L,
        null,
        null,
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        4,
        1024L,
        6,
        4,
        List.of("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64)));
  }

  private FragmentPlacement samplePlacement(final String fileId) {
    return new FragmentPlacement(
        fileId,
        "frag-" + fileId,
        0,
        0,
        false,
        "node-c",
        "http://node-c:8080",
        "agr-" + fileId,
        "a".repeat(64),
        1024L,
        Instant.parse("2026-04-01T00:00:00Z"),
        es.ual.node.filesystem.domain.FragmentHealthStatus.OK,
        null,
        0);
  }

  /** Test-only store que falla en el primer {@code store(...)} y luego acepta. */
  private static final class FailingFirstStore extends InMemoryRemoteFileManifestStoreAdapter {
    private boolean firstFailed = false;

    @Override
    public void store(
        final FileManifest manifest,
        final List<FragmentPlacement> placements,
        final String tutorBaseUrl) {
      if (!firstFailed) {
        firstFailed = true;
        throw new IllegalStateException("simulated failure on first store");
      }
      super.store(manifest, placements, tutorBaseUrl);
    }
  }
}
