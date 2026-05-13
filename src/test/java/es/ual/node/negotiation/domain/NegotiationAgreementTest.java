package es.ual.node.negotiation.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link NegotiationAgreement}, focused on tutor-propagation fields. */
class NegotiationAgreementTest {

  private static final String AGREEMENT_ID = "agreement-1";
  private static final String REQUESTER = "requester-node";
  private static final String TARGET = "target-node";
  private static final Instant CREATED = Instant.parse("2026-04-27T10:00:00Z");
  private static final Instant EXPIRES = Instant.parse("2026-04-27T10:05:00Z");

  @Test
  void shouldCreateAgreementWithTutorInfoWhenProvided() {
    NegotiationAgreement agreement = newAgreement("tutor-x", "https://tutor-x.example");

    assertEquals("tutor-x", agreement.requesterTutorNodeId());
    assertEquals("https://tutor-x.example", agreement.requesterTutorBaseUrl());
  }

  @Test
  void shouldCreateAgreementWithoutTutorViaConvenienceConstructor() {
    NegotiationAgreement agreement =
        new NegotiationAgreement(
            AGREEMENT_ID,
            REQUESTER,
            TARGET,
            NegotiationStatus.PENDING,
            TransferMode.FRAGMENTS_ONLY,
            1024L,
            4096L,
            4,
            "RS(6,4)",
            1_000_000L,
            null,
            CREATED,
            EXPIRES,
            "requester-signature",
            null,
            null);

    assertNull(agreement.requesterTutorNodeId());
    assertNull(agreement.requesterTutorBaseUrl());
  }

  @Test
  void confirmShouldPreserveTutorInfo() {
    NegotiationAgreement pending = newAgreement("tutor-x", "https://tutor-x.example");

    NegotiationAgreement confirmed = pending.confirm("target-signature", null);

    assertEquals("tutor-x", confirmed.requesterTutorNodeId());
    assertEquals("https://tutor-x.example", confirmed.requesterTutorBaseUrl());
    assertEquals(NegotiationStatus.CONFIRMED, confirmed.status());
  }

  @Test
  void rejectShouldPreserveTutorInfo() {
    NegotiationAgreement pending = newAgreement("tutor-x", "https://tutor-x.example");

    NegotiationAgreement rejected = pending.reject();

    assertEquals("tutor-x", rejected.requesterTutorNodeId());
    assertEquals("https://tutor-x.example", rejected.requesterTutorBaseUrl());
  }

  @Test
  void cancelAndExpireShouldPreserveTutorInfo() {
    NegotiationAgreement pending = newAgreement("tutor-x", "https://tutor-x.example");

    NegotiationAgreement cancelled = pending.cancel();
    NegotiationAgreement expired = pending.expire();

    assertEquals("tutor-x", cancelled.requesterTutorNodeId());
    assertEquals("https://tutor-x.example", cancelled.requesterTutorBaseUrl());
    assertEquals("tutor-x", expired.requesterTutorNodeId());
    assertEquals("https://tutor-x.example", expired.requesterTutorBaseUrl());
  }

  @Test
  void shouldRejectBlankRequesterTutorNodeId() {
    assertThrows(IllegalArgumentException.class, () -> newAgreement("  ", "https://tutor.example"));
  }

  @Test
  void shouldRejectBlankRequesterTutorBaseUrl() {
    assertThrows(IllegalArgumentException.class, () -> newAgreement("tutor-x", "  "));
  }

  private NegotiationAgreement newAgreement(
      final String requesterTutorNodeId, final String requesterTutorBaseUrl) {
    return new NegotiationAgreement(
        AGREEMENT_ID,
        REQUESTER,
        TARGET,
        NegotiationStatus.PENDING,
        TransferMode.FRAGMENTS_ONLY,
        1024L,
        4096L,
        4,
        "RS(6,4)",
        1_000_000L,
        null,
        CREATED,
        EXPIRES,
        "requester-signature",
        null,
        null,
        requesterTutorNodeId,
        requesterTutorBaseUrl);
  }
}
