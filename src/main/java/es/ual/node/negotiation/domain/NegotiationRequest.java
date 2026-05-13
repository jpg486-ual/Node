package es.ual.node.negotiation.domain;

import java.time.Instant;
import java.util.Objects;

/** Immutable request payload for a storage negotiation attempt. */
public final class NegotiationRequest {

  private final String requestId;
  private final String requesterNodeId;
  private final String providerNodeId;
  private final String fragmentId;
  private final long sizeBytes;
  private final String checksum;
  private final Instant requestedAt;

  /**
   * Creates a validated negotiation request.
   *
   * @param requestId request identifier
   * @param requesterNodeId requester node identifier
   * @param providerNodeId provider node identifier
   * @param fragmentId fragment identifier
   * @param sizeBytes requested fragment size
   * @param checksum fragment checksum
   * @param requestedAt request timestamp
   */
  public NegotiationRequest(
      final String requestId,
      final String requesterNodeId,
      final String providerNodeId,
      final String fragmentId,
      final long sizeBytes,
      final String checksum,
      final Instant requestedAt) {
    if (requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("requestId must not be blank");
    }
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      throw new IllegalArgumentException("requesterNodeId must not be blank");
    }
    if (providerNodeId == null || providerNodeId.isBlank()) {
      throw new IllegalArgumentException("providerNodeId must not be blank");
    }
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    if (sizeBytes <= 0) {
      throw new IllegalArgumentException("sizeBytes must be greater than zero");
    }
    if (checksum == null || checksum.isBlank()) {
      throw new IllegalArgumentException("checksum must not be blank");
    }
    if (requestedAt == null) {
      throw new IllegalArgumentException("requestedAt must not be null");
    }

    this.requestId = requestId.trim();
    this.requesterNodeId = requesterNodeId.trim();
    this.providerNodeId = providerNodeId.trim();
    this.fragmentId = fragmentId.trim();
    this.sizeBytes = sizeBytes;
    this.checksum = checksum.trim();
    this.requestedAt = requestedAt;
  }

  /**
   * Returns request identifier.
   *
   * @return request identifier
   */
  public String requestId() {
    return requestId;
  }

  /**
   * Returns requester node identifier.
   *
   * @return requester node identifier
   */
  public String requesterNodeId() {
    return requesterNodeId;
  }

  /**
   * Returns provider node identifier.
   *
   * @return provider node identifier
   */
  public String providerNodeId() {
    return providerNodeId;
  }

  /**
   * Returns fragment identifier.
   *
   * @return fragment identifier
   */
  public String fragmentId() {
    return fragmentId;
  }

  /**
   * Returns requested size in bytes.
   *
   * @return requested size
   */
  public long sizeBytes() {
    return sizeBytes;
  }

  /**
   * Returns checksum value.
   *
   * @return checksum value
   */
  public String checksum() {
    return checksum;
  }

  /**
   * Returns request timestamp.
   *
   * @return request timestamp
   */
  public Instant requestedAt() {
    return requestedAt;
  }

  /**
   * Compares this request with another object.
   *
   * @param o object to compare
   * @return {@code true} when both requests are equal
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof NegotiationRequest that)) {
      return false;
    }
    return sizeBytes == that.sizeBytes
        && Objects.equals(requestId, that.requestId)
        && Objects.equals(requesterNodeId, that.requesterNodeId)
        && Objects.equals(providerNodeId, that.providerNodeId)
        && Objects.equals(fragmentId, that.fragmentId)
        && Objects.equals(checksum, that.checksum)
        && Objects.equals(requestedAt, that.requestedAt);
  }

  /**
   * Returns hash code for this request.
   *
   * @return hash code
   */
  @Override
  public int hashCode() {
    return Objects.hash(
        requestId, requesterNodeId, providerNodeId, fragmentId, sizeBytes, checksum, requestedAt);
  }
}
