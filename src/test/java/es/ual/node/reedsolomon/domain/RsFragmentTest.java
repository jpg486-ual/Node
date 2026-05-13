package es.ual.node.reedsolomon.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RsFragment}. */
class RsFragmentTest {

  @Test
  void shouldCreateFragmentWithDefensiveCopy() {
    final byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);

    final RsFragment fragment =
        new RsFragment("fragment-1", 0, false, "checksum-1", payload.length, payload);

    payload[0] = 'X';

    assertEquals("fragment-1", fragment.fragmentId());
    assertEquals(0, fragment.index());
    assertEquals(payload.length, fragment.payloadSize());
    assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), fragment.payload());

    final byte[] exported = fragment.payload();
    exported[1] = 'Y';
    assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), fragment.payload());
  }

  @Test
  void shouldRejectFragmentWhenPayloadSizeDoesNotMatch() {
    final byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);

    assertThrows(
        IllegalArgumentException.class,
        () -> new RsFragment("fragment-1", 0, false, "checksum-1", payload.length + 1, payload));
  }

  @Test
  void shouldRejectFragmentWhenIndexIsNegative() {
    final byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);

    assertThrows(
        IllegalArgumentException.class,
        () -> new RsFragment("fragment-1", -1, false, "checksum-1", payload.length, payload));
  }

  @Test
  void shouldRejectFragmentWhenChecksumIsBlank() {
    final byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);

    assertThrows(
        IllegalArgumentException.class,
        () -> new RsFragment("fragment-1", 0, false, "  ", payload.length, payload));
  }
}
