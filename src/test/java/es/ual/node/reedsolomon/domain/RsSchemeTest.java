package es.ual.node.reedsolomon.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link RsScheme}. */
class RsSchemeTest {

  @Test
  void shouldCreateSchemeWhenValuesAreValid() {
    final RsScheme scheme = new RsScheme(6, 4, 1024);

    assertEquals(6, scheme.n());
    assertEquals(4, scheme.k());
    assertEquals(1024, scheme.symbolSize());
    assertEquals(2, scheme.parityFragmentCount());
  }

  @Test
  void shouldRejectSchemeWhenNIsLowerThanK() {
    assertThrows(IllegalArgumentException.class, () -> new RsScheme(3, 4, 1024));
  }

  @Test
  void shouldRejectSchemeWhenNIsNotPositive() {
    assertThrows(IllegalArgumentException.class, () -> new RsScheme(0, 1, 1024));
  }

  @Test
  void shouldRejectSchemeWhenSymbolSizeIsNotPositive() {
    assertThrows(IllegalArgumentException.class, () -> new RsScheme(4, 2, 0));
  }
}
