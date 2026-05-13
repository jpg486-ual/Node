package es.ual.node.reedsolomon.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RsManifest}. */
class RsManifestTest {

  @Test
  void shouldCreateManifestWhenArgumentsAreValid() {
    final RsScheme scheme = new RsScheme(3, 2, 1024);
    final List<RsFragment> fragments = new ArrayList<>();
    fragments.add(fragment("fragment-0", 0, false, "checksum-0", "a"));
    fragments.add(fragment("fragment-1", 1, false, "checksum-1", "b"));
    fragments.add(fragment("fragment-2", 2, true, "checksum-2", "c"));

    final RsManifest manifest = new RsManifest("file-1", "hash-1", scheme, fragments, 1L);

    fragments.clear();

    assertEquals("file-1", manifest.fileId());
    assertEquals("hash-1", manifest.originalHash());
    assertEquals(3, manifest.fragments().size());
    assertEquals(1L, manifest.version());
  }

  @Test
  void shouldRejectManifestWhenFragmentCountDoesNotMatchSchemeN() {
    final RsScheme scheme = new RsScheme(3, 2, 1024);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RsManifest(
                "file-1",
                "hash-1",
                scheme,
                List.of(
                    fragment("fragment-0", 0, false, "checksum-0", "a"),
                    fragment("fragment-1", 1, false, "checksum-1", "b")),
                1L));
  }

  @Test
  void shouldRejectManifestWhenDuplicateFragmentIndexExists() {
    final RsScheme scheme = new RsScheme(3, 2, 1024);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RsManifest(
                "file-1",
                "hash-1",
                scheme,
                List.of(
                    fragment("fragment-0", 0, false, "checksum-0", "a"),
                    fragment("fragment-1", 0, false, "checksum-1", "b"),
                    fragment("fragment-2", 2, true, "checksum-2", "c")),
                1L));
  }

  @Test
  void shouldRejectManifestWhenFragmentIndexIsOutOfRange() {
    final RsScheme scheme = new RsScheme(3, 2, 1024);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RsManifest(
                "file-1",
                "hash-1",
                scheme,
                List.of(
                    fragment("fragment-0", 0, false, "checksum-0", "a"),
                    fragment("fragment-1", 1, false, "checksum-1", "b"),
                    fragment("fragment-2", 3, true, "checksum-2", "c")),
                1L));
  }

  private RsFragment fragment(
      final String fragmentId,
      final int index,
      final boolean parity,
      final String checksum,
      final String payload) {
    final byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    return new RsFragment(fragmentId, index, parity, checksum, bytes.length, bytes);
  }
}
