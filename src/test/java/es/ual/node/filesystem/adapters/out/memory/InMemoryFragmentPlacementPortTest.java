package es.ual.node.filesystem.adapters.out.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.ports.out.FragmentPlacementPort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link InMemoryFragmentPlacementPort}. */
class InMemoryFragmentPlacementPortTest {

  private static final String FILE_ID = "00000000-0000-0000-0000-000000000001";
  private static final String CHECKSUM = "a".repeat(64);

  private FragmentPlacementPort sut;

  @BeforeEach
  void setUp() {
    sut = new InMemoryFragmentPlacementPort();
  }

  @Test
  void persistsAndReadsBackOrderedByIndex() {
    final FragmentPlacement p2 = placement(2, true, "node-c");
    final FragmentPlacement p0 = placement(0, false, "node-a");
    final FragmentPlacement p1 = placement(1, false, "node-b");
    sut.save(p2);
    sut.save(p0);
    sut.save(p1);

    final List<FragmentPlacement> readBack = sut.findByFileId(FILE_ID);
    assertEquals(3, readBack.size());
    assertEquals(0, readBack.get(0).fragmentIndex());
    assertEquals(1, readBack.get(1).fragmentIndex());
    assertEquals(2, readBack.get(2).fragmentIndex());
    assertEquals("node-a", readBack.get(0).custodianNodeId());
    assertEquals("node-c", readBack.get(2).custodianNodeId());
    assertTrue(readBack.get(2).parity());
  }

  @Test
  void emptyForUnknownFileId() {
    assertEquals(List.of(), sut.findByFileId(FILE_ID));
    assertEquals(List.of(), sut.findByFileId(""));
    assertEquals(List.of(), sut.findByFileId(null));
  }

  @Test
  void deleteByFileIdRemovesAllPlacements() {
    sut.save(placement(0, false, "node-a"));
    sut.save(placement(1, false, "node-b"));
    sut.deleteByFileId(FILE_ID);
    assertEquals(List.of(), sut.findByFileId(FILE_ID));
  }

  @Test
  void deleteByFileIdIsIdempotent() {
    sut.deleteByFileId(FILE_ID);
    sut.deleteByFileId("");
    sut.deleteByFileId(null);
  }

  @Test
  void rejectsNullPlacement() {
    assertThrows(IllegalArgumentException.class, () -> sut.save(null));
  }

  // ---------- findAll() + deleteByFileIdAndFragmentId ----------

  @Test
  void findAllReturnsEveryPlacementOrdered() {
    sut.save(placementForFile("file-A", 1, "node-a"));
    sut.save(placementForFile("file-B", 0, "node-b"));
    sut.save(placementForFile("file-A", 0, "node-c"));

    final List<FragmentPlacement> all = sut.findAll();
    assertEquals(3, all.size());
    // ordering: fileId asc, blockIndex asc, fragmentIndex asc
    assertEquals("file-A", all.get(0).fileId());
    assertEquals(0, all.get(0).fragmentIndex());
    assertEquals("file-A", all.get(1).fileId());
    assertEquals(1, all.get(1).fragmentIndex());
    assertEquals("file-B", all.get(2).fileId());
  }

  @Test
  void deleteByFileIdAndFragmentIdRemovesOnlyOnePlacement() {
    sut.save(placement(0, false, "node-a"));
    sut.save(placement(1, false, "node-b"));
    sut.save(placement(2, true, "node-c"));

    sut.deleteByFileIdAndFragmentId(FILE_ID, "fragment-1");

    final List<FragmentPlacement> remaining = sut.findByFileId(FILE_ID);
    assertEquals(2, remaining.size());
    assertEquals(0, remaining.get(0).fragmentIndex());
    assertEquals(2, remaining.get(1).fragmentIndex());
  }

  @Test
  void deleteByFileIdAndFragmentIdIsIdempotentForUnknown() {
    sut.deleteByFileIdAndFragmentId("ghost", "ghost-frag");
    sut.deleteByFileIdAndFragmentId("", "");
    sut.deleteByFileIdAndFragmentId(null, null);
  }

  private FragmentPlacement placementForFile(
      final String fileId, final int index, final String custodianNodeId) {
    return new FragmentPlacement(
        fileId,
        fileId + "-frag-" + index,
        index,
        index >= 2,
        custodianNodeId,
        "http://" + custodianNodeId + ":8080",
        "agreement-" + fileId + "-" + index,
        CHECKSUM,
        1024L,
        Instant.parse("2026-05-01T19:00:00Z"));
  }

  @Test
  void multiBlockPlacementsAreReadBackOrderedByBlockThenFragment() {
    // Con varios bloques, findByFileId ordena (blockIndex asc, fragmentIndex asc) para que
    // reconstructDownload itere bloques en orden.
    sut.save(placementAtBlock(1, 0, "node-a"));
    sut.save(placementAtBlock(0, 2, "node-c"));
    sut.save(placementAtBlock(0, 0, "node-a"));
    sut.save(placementAtBlock(1, 2, "node-c"));
    sut.save(placementAtBlock(0, 1, "node-b"));
    sut.save(placementAtBlock(1, 1, "node-b"));

    final List<FragmentPlacement> readBack = sut.findByFileId(FILE_ID);

    assertEquals(6, readBack.size());
    // Block 0 first
    assertEquals(0, readBack.get(0).blockIndex());
    assertEquals(0, readBack.get(0).fragmentIndex());
    assertEquals(0, readBack.get(1).blockIndex());
    assertEquals(1, readBack.get(1).fragmentIndex());
    assertEquals(0, readBack.get(2).blockIndex());
    assertEquals(2, readBack.get(2).fragmentIndex());
    // Block 1 second
    assertEquals(1, readBack.get(3).blockIndex());
    assertEquals(0, readBack.get(3).fragmentIndex());
    assertEquals(1, readBack.get(5).blockIndex());
    assertEquals(2, readBack.get(5).fragmentIndex());
  }

  private FragmentPlacement placement(
      final int index, final boolean parity, final String custodianNodeId) {
    return new FragmentPlacement(
        FILE_ID,
        "fragment-" + index,
        index,
        parity,
        custodianNodeId,
        "http://" + custodianNodeId + ":8080",
        "agreement-" + index,
        CHECKSUM,
        1024L,
        Instant.parse("2026-05-01T19:00:00Z"));
  }

  private FragmentPlacement placementAtBlock(
      final int blockIndex, final int fragmentIndex, final String custodianNodeId) {
    return new FragmentPlacement(
        FILE_ID,
        "fragment-b" + blockIndex + "-f" + fragmentIndex,
        blockIndex,
        fragmentIndex,
        fragmentIndex >= 2,
        custodianNodeId,
        "http://" + custodianNodeId + ":8080",
        "agreement-b" + blockIndex + "-f" + fragmentIndex,
        CHECKSUM,
        1024L,
        Instant.parse("2026-05-01T19:00:00Z"));
  }
}
