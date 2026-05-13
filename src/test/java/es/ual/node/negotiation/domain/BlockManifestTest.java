package es.ual.node.negotiation.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BlockManifest} (RS por bloques). */
class BlockManifestTest {

  private static final String VALID_HASH =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  @Test
  void shouldCreateValidBlock() {
    final BlockManifest block =
        new BlockManifest(0, 4096L, VALID_HASH, List.of(VALID_HASH, VALID_HASH, VALID_HASH));

    assertEquals(0, block.blockIndex());
    assertEquals(4096L, block.blockSizeBytes());
    assertEquals(VALID_HASH, block.blockHash());
    assertEquals(3, block.fragmentHashes().size());
  }

  @Test
  void shouldRejectNegativeBlockIndex() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new BlockManifest(-1, 4096L, VALID_HASH, List.of(VALID_HASH)));
  }

  @Test
  void shouldRejectZeroBlockSize() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new BlockManifest(0, 0L, VALID_HASH, List.of(VALID_HASH)));
  }

  @Test
  void shouldRejectInvalidBlockHash() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new BlockManifest(0, 4096L, "not-a-hash", List.of(VALID_HASH)));
  }

  @Test
  void shouldRejectEmptyFragmentHashes() {
    assertThrows(
        IllegalArgumentException.class, () -> new BlockManifest(0, 4096L, VALID_HASH, List.of()));
  }

  @Test
  void shouldRejectFragmentHashesContainingInvalidValue() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new BlockManifest(0, 4096L, VALID_HASH, List.of(VALID_HASH, "bad")));
  }

  @Test
  void shouldNormalizeBlockHashToLowerCase() {
    final BlockManifest block =
        new BlockManifest(
            0, 4096L, VALID_HASH.toUpperCase(java.util.Locale.ROOT), List.of(VALID_HASH));

    assertEquals(VALID_HASH, block.blockHash());
  }
}
