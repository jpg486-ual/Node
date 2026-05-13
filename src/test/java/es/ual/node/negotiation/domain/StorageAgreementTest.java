package es.ual.node.negotiation.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link StorageAgreement}. */
class StorageAgreementTest {

  @Test
  void shouldCreateAgreementWhenArgumentsAreValid() {
    StorageAgreement agreement =
        new StorageAgreement(
            "agr-1",
            "node-a",
            "node-b",
            "fragment-1",
            Instant.parse("2026-02-13T10:00:00Z"),
            Instant.parse("2026-02-13T10:05:00Z"),
            "req-signature",
            null);

    assertFalse(agreement.isExpired(Instant.parse("2026-02-13T10:01:00Z")));
    assertFalse(agreement.isFullySigned());
  }

  @Test
  void shouldMarkAgreementAsFullySignedAfterProviderConfirmation() {
    StorageAgreement agreement =
        new StorageAgreement(
            "agr-1",
            "node-a",
            "node-b",
            "fragment-1",
            Instant.parse("2026-02-13T10:00:00Z"),
            Instant.parse("2026-02-13T10:05:00Z"),
            "req-signature",
            null);

    StorageAgreement confirmed = agreement.confirmByProvider("provider-signature");

    assertTrue(confirmed.isFullySigned());
  }

  @Test
  void shouldRejectAgreementWhenExpirationIsNotAfterCreation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StorageAgreement(
                "agr-1",
                "node-a",
                "node-b",
                "fragment-1",
                Instant.parse("2026-02-13T10:00:00Z"),
                Instant.parse("2026-02-13T10:00:00Z"),
                "req-signature",
                null));
  }
}
