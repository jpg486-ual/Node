package es.ual.node.filesystem.adapters.out.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.negotiation.domain.FileManifest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link InMemoryRemoteFileManifestStoreAdapter}. */
class InMemoryRemoteFileManifestStoreAdapterTest {

  private static final String TUTOR = "http://node2:8080";

  @Test
  void store_recordsManifestAndPlacements_andSurfacesViaFindRecord() {
    final InMemoryRemoteFileManifestStoreAdapter sut = new InMemoryRemoteFileManifestStoreAdapter();
    final FileManifest manifest = sampleManifest();
    final List<FragmentPlacement> placements = samplePlacements(manifest.fileId());

    sut.store(manifest, placements, TUTOR);

    final InMemoryRemoteFileManifestStoreAdapter.ReplicationRecord record =
        sut.findRecord(TUTOR, manifest.fileId());
    assertNotNull(record, "expected record after store");
    assertEquals(manifest.fileId(), record.manifest().fileId());
    assertEquals(placements.size(), record.placements().size());
    assertEquals(1, sut.recordCount());
  }

  @Test
  void store_acceptsEmptyPlacements() {
    final InMemoryRemoteFileManifestStoreAdapter sut = new InMemoryRemoteFileManifestStoreAdapter();
    final FileManifest manifest = sampleManifest();

    sut.store(manifest, List.of(), TUTOR);

    final InMemoryRemoteFileManifestStoreAdapter.ReplicationRecord record =
        sut.findRecord(TUTOR, manifest.fileId());
    assertNotNull(record);
    assertEquals(0, record.placements().size());
  }

  @Test
  void store_overwritesExistingRecordForSameFileIdAndTutor() {
    final InMemoryRemoteFileManifestStoreAdapter sut = new InMemoryRemoteFileManifestStoreAdapter();
    final FileManifest manifest = sampleManifest();
    sut.store(manifest, List.of(), TUTOR);
    sut.store(manifest, samplePlacements(manifest.fileId()), TUTOR);

    final InMemoryRemoteFileManifestStoreAdapter.ReplicationRecord record =
        sut.findRecord(TUTOR, manifest.fileId());
    assertEquals(1, sut.recordCount());
    assertEquals(3, record.placements().size());
  }

  @Test
  void store_keysSeparatelyByTutorBaseUrl() {
    final InMemoryRemoteFileManifestStoreAdapter sut = new InMemoryRemoteFileManifestStoreAdapter();
    final FileManifest manifest = sampleManifest();
    sut.store(manifest, List.of(), "http://tutor-a:8080");
    sut.store(manifest, List.of(), "http://tutor-b:8080");

    assertEquals(2, sut.recordCount());
    assertNotNull(sut.findRecord("http://tutor-a:8080", manifest.fileId()));
    assertNotNull(sut.findRecord("http://tutor-b:8080", manifest.fileId()));
  }

  @Test
  void delete_removesRecord_andIsIdempotent() {
    final InMemoryRemoteFileManifestStoreAdapter sut = new InMemoryRemoteFileManifestStoreAdapter();
    final FileManifest manifest = sampleManifest();
    sut.store(manifest, List.of(), TUTOR);

    sut.delete(manifest.fileId(), TUTOR);
    assertEquals(0, sut.recordCount());

    sut.delete(manifest.fileId(), TUTOR); // idempotent
    assertEquals(0, sut.recordCount());
  }

  @Test
  void store_rejectsBlankInputs() {
    final InMemoryRemoteFileManifestStoreAdapter sut = new InMemoryRemoteFileManifestStoreAdapter();
    final FileManifest manifest = sampleManifest();

    assertThrows(IllegalArgumentException.class, () -> sut.store(null, List.of(), TUTOR));
    assertThrows(IllegalArgumentException.class, () -> sut.store(manifest, null, TUTOR));
    assertThrows(IllegalArgumentException.class, () -> sut.store(manifest, List.of(), ""));
    assertThrows(IllegalArgumentException.class, () -> sut.store(manifest, List.of(), null));
  }

  @Test
  void delete_rejectsBlankInputs() {
    final InMemoryRemoteFileManifestStoreAdapter sut = new InMemoryRemoteFileManifestStoreAdapter();
    assertThrows(IllegalArgumentException.class, () -> sut.delete("", TUTOR));
    assertThrows(IllegalArgumentException.class, () -> sut.delete(null, TUTOR));
    assertThrows(IllegalArgumentException.class, () -> sut.delete("file-1", ""));
    assertThrows(IllegalArgumentException.class, () -> sut.delete("file-1", null));
  }

  private static FileManifest sampleManifest() {
    final String fileId = UUID.randomUUID().toString();
    return new FileManifest(
        fileId,
        "/jose/photos",
        "vacation.jpg",
        1024L,
        null,
        null,
        "0".repeat(64),
        3,
        512L,
        3,
        2,
        List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)));
  }

  private static List<FragmentPlacement> samplePlacements(final String fileId) {
    final Instant now = Instant.now();
    return List.of(
        new FragmentPlacement(
            fileId,
            "frag-0",
            0,
            0,
            false,
            "node-1",
            "http://node1:8080",
            "agreement-1",
            "a".repeat(64),
            512L,
            now),
        new FragmentPlacement(
            fileId,
            "frag-1",
            0,
            1,
            false,
            "node-2",
            "http://node2:8080",
            "agreement-2",
            "b".repeat(64),
            512L,
            now),
        new FragmentPlacement(
            fileId,
            "frag-2",
            0,
            2,
            true,
            "node-3",
            "http://node3:8080",
            "agreement-3",
            "c".repeat(64),
            512L,
            now));
  }
}
