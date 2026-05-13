package es.ual.node.reedsolomon.adapters.out.memory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import es.ual.node.reedsolomon.domain.RsFragment;
import es.ual.node.reedsolomon.domain.RsScheme;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link InMemoryRsCodecAdapter}. */
class InMemoryRsCodecAdapterTest {

  @Test
  void shouldEncodeNFragmentsAndReconstructWithFullSet() {
    final InMemoryRsCodecAdapter adapter = new InMemoryRsCodecAdapter();
    final RsScheme scheme = new RsScheme(6, 4, 128);
    final byte[] input = "fragmented-payload-for-reed-solomon".getBytes(StandardCharsets.UTF_8);

    final List<RsFragment> fragments = adapter.encode(input, scheme);

    assertEquals(6, fragments.size());
    assertEquals(2, fragments.stream().filter(RsFragment::isParity).count());
    assertArrayEquals(input, adapter.reconstruct(fragments, scheme));
  }

  @Test
  void shouldReconstructWhenAtMostNMinusKFragmentsAreMissing() {
    final InMemoryRsCodecAdapter adapter = new InMemoryRsCodecAdapter();
    final RsScheme scheme = new RsScheme(6, 4, 128);
    final byte[] input = "payload-with-loss".getBytes(StandardCharsets.UTF_8);

    final List<RsFragment> fragments = adapter.encode(input, scheme);
    final List<RsFragment> degraded = new ArrayList<>(fragments);
    degraded.removeIf(fragment -> fragment.index() == 1 || fragment.index() == 5);
    degraded.sort(Comparator.comparingInt(RsFragment::index));

    assertArrayEquals(input, adapter.reconstruct(degraded, scheme));
  }

  @Test
  void shouldFailWhenAvailableFragmentsAreLowerThanK() {
    final InMemoryRsCodecAdapter adapter = new InMemoryRsCodecAdapter();
    final RsScheme scheme = new RsScheme(6, 4, 128);
    final byte[] input = "payload-insufficient".getBytes(StandardCharsets.UTF_8);

    final List<RsFragment> fragments = adapter.encode(input, scheme);
    final List<RsFragment> insufficient =
        List.of(fragments.get(0), fragments.get(1), fragments.get(2));

    assertThrows(IllegalArgumentException.class, () -> adapter.reconstruct(insufficient, scheme));
  }

  @Test
  void shouldFailWhenFragmentPayloadDoesNotMatchChecksum() {
    final InMemoryRsCodecAdapter adapter = new InMemoryRsCodecAdapter();
    final RsScheme scheme = new RsScheme(6, 4, 128);
    final byte[] input = "payload-corrupted".getBytes(StandardCharsets.UTF_8);

    final List<RsFragment> fragments = new ArrayList<>(adapter.encode(input, scheme));
    final RsFragment original = fragments.getFirst();
    final byte[] corruptedPayload = original.payload();
    corruptedPayload[0] = (byte) (corruptedPayload[0] ^ 0x7f);

    fragments.set(
        0,
        new RsFragment(
            original.fragmentId(),
            original.index(),
            original.isParity(),
            original.checksum(),
            corruptedPayload.length,
            corruptedPayload));

    assertThrows(IllegalArgumentException.class, () -> adapter.reconstruct(fragments, scheme));
  }
}
