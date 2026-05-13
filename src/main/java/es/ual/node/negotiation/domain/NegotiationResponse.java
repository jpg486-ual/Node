package es.ual.node.negotiation.domain;

import java.time.Instant;
import java.util.Objects;

/** Immutable response payload for negotiation outcomes. */
public final class NegotiationResponse {

  private final String requestId;
  private final Status status;
  private final String agreementId;
  private final String reason;
  private final Instant respondedAt;

  /** Enumeration of response statuses. */
  public enum Status {
    ACCEPTED,
    REJECTED
  }

  /**
   * Creates a validated negotiation response.
   *
   * @param requestId request identifier
   * @param status response status
   * @param agreementId agreement identifier for accepted responses
   * @param reason rejection reason for rejected responses
   * @param respondedAt response timestamp
   */
  public NegotiationResponse(
      final String requestId,
      final Status status,
      final String agreementId,
      final String reason,
      final Instant respondedAt) {
    if (requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("requestId must not be blank");
    }
    if (status == null) {
      throw new IllegalArgumentException("status must not be null");
    }
    if (respondedAt == null) {
      throw new IllegalArgumentException("respondedAt must not be null");
    }

    if (status == Status.ACCEPTED && (agreementId == null || agreementId.isBlank())) {
      throw new IllegalArgumentException("agreementId must not be blank for accepted responses");
    }
    if (status == Status.REJECTED && (reason == null || reason.isBlank())) {
      throw new IllegalArgumentException("reason must not be blank for rejected responses");
    }

    this.requestId = requestId.trim();
    this.status = status;
    this.agreementId = agreementId == null ? null : agreementId.trim();
    this.reason = reason == null ? null : reason.trim();
    this.respondedAt = respondedAt;
  }

  /**
   * Creates an accepted response.
   *
   * @param requestId request identifier
   * @param agreementId resulting agreement identifier
   * @param respondedAt response timestamp
   * @return accepted response
   */
  public static NegotiationResponse accepted(
      final String requestId, final String agreementId, final Instant respondedAt) {
    return new NegotiationResponse(requestId, Status.ACCEPTED, agreementId, null, respondedAt);
  }

  /**
   * Creates a rejected response.
   *
   * @param requestId request identifier
   * @param reason rejection reason
   * @param respondedAt response timestamp
   * @return rejected response
   */
  public static NegotiationResponse rejected(
      final String requestId, final String reason, final Instant respondedAt) {
    return new NegotiationResponse(requestId, Status.REJECTED, null, reason, respondedAt);
  }

  /**
   * Indicates whether response status is accepted.
   *
   * @return {@code true} if accepted
   */
  public boolean isAccepted() {
    return status == Status.ACCEPTED;
  }

  /**
   * Indicates whether response status is rejected.
   *
   * @return {@code true} if rejected
   */
  public boolean isRejected() {
    return status == Status.REJECTED;
  }

  /**
   * Returns the request identifier.
   *
   * @return request identifier
   */
  public String requestId() {
    return requestId;
  }

  /**
   * Returns response status.
   *
   * @return response status
   */
  public Status status() {
    return status;
  }

  /**
   * Returns agreement identifier for accepted responses.
   *
   * @return agreement identifier or null
   */
  public String agreementId() {
    return agreementId;
  }

  /**
   * Returns rejection reason for rejected responses.
   *
   * @return rejection reason or null
   */
  public String reason() {
    return reason;
  }

  /**
   * Returns response timestamp.
   *
   * @return response timestamp
   */
  public Instant respondedAt() {
    return respondedAt;
  }

  /**
   * Compares this response with another object.
   *
   * @param o object to compare
   * @return {@code true} when both responses are equal
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof NegotiationResponse that)) {
      return false;
    }
    return Objects.equals(requestId, that.requestId)
        && status == that.status
        && Objects.equals(agreementId, that.agreementId)
        && Objects.equals(reason, that.reason)
        && Objects.equals(respondedAt, that.respondedAt);
  }

  /**
   * Returns hash code for this response.
   *
   * @return hash code
   */
  @Override
  public int hashCode() {
    return Objects.hash(requestId, status, agreementId, reason, respondedAt);
  }
}
