package es.ual.node.shared.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link FailureDomain}. */
class FailureDomainTest {

  @Test
  void shouldCreateFailureDomainWhenValueIsValid() {
    FailureDomain domain = FailureDomain.of("zone-a/rack-1");

    assertEquals("zone-a/rack-1", domain.toString());
  }

  @Test
  void shouldRejectBlankFailureDomain() {
    assertThrows(IllegalArgumentException.class, () -> FailureDomain.of(" "));
  }

  @Test
  void shouldRejectFailureDomainWithInvalidCharacters() {
    assertThrows(IllegalArgumentException.class, () -> FailureDomain.of("zone a/rack-1"));
  }

  @Test
  void shouldMatchWhenCurrentDomainStartsWithCandidateRoot() {
    FailureDomain current = FailureDomain.of("zone-a/rack-1");
    FailureDomain candidate = FailureDomain.of("zone-a");

    assertTrue(current.matches(candidate));
  }

  @Test
  void shouldNotMatchWhenCurrentDomainDoesNotStartWithCandidateRoot() {
    FailureDomain current = FailureDomain.of("zone-b/rack-1");
    FailureDomain candidate = FailureDomain.of("zone-a");

    assertFalse(current.matches(candidate));
  }
}
