package es.ual.node.negotiation.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link NegotiationRequest} and {@link NegotiationResponse}. */
class NegotiationRequestResponseTest {

  @Test
  void shouldCreateNegotiationRequestWhenArgumentsAreValid() {
    NegotiationRequest request =
        new NegotiationRequest(
            "req-1",
            "node-a",
            "node-b",
            "fragment-1",
            4096,
            "def123",
            Instant.parse("2026-02-13T10:00:00Z"));

    assertTrue(request.sizeBytes() > 0);
  }

  @Test
  void shouldRejectNegotiationRequestWhenSizeIsInvalid() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NegotiationRequest(
                "req-1", "node-a", "node-b", "fragment-1", 0, "def123", Instant.now()));
  }

  @Test
  void shouldCreateAcceptedResponseWhenAgreementIdIsProvided() {
    NegotiationResponse response =
        NegotiationResponse.accepted("req-1", "agr-1", Instant.parse("2026-02-13T10:01:00Z"));

    assertTrue(response.isAccepted());
    assertFalse(response.isRejected());
  }

  @Test
  void shouldCreateRejectedResponseWhenReasonIsProvided() {
    NegotiationResponse response =
        NegotiationResponse.rejected(
            "req-1", "capacity exhausted", Instant.parse("2026-02-13T10:01:00Z"));

    assertTrue(response.isRejected());
    assertFalse(response.isAccepted());
  }

  @Test
  void shouldRejectAcceptedResponseWithoutAgreementId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NegotiationResponse(
                "req-1", NegotiationResponse.Status.ACCEPTED, " ", null, Instant.now()));
  }
}
