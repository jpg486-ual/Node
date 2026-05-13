package es.ual.node.negotiation.domain;

import java.time.Instant;
import java.util.Objects;

/** Aggregate representing a storage agreement between requester and provider nodes. */
public final class StorageAgreement {

  private final String agreementId;
  private final String requesterNodeId;
  private final String providerNodeId;
  private final String fragmentId;
  private final Instant createdAt;
  private final Instant expiresAt;
  private final String requesterSignature;
  private final String providerSignature;

  /**
   * Creates a storage agreement with validated attributes.
   *
   * @param agreementId agreement identifier
   * @param requesterNodeId requester node identifier
   * @param providerNodeId provider node identifier
   * @param fragmentId target fragment identifier
   * @param createdAt agreement creation timestamp
   * @param expiresAt agreement expiration timestamp
   * @param requesterSignature requester signature
   * @param providerSignature provider signature, may be null until confirmation
   */
  public StorageAgreement(
      final String agreementId,
      final String requesterNodeId,
      final String providerNodeId,
      final String fragmentId,
      final Instant createdAt,
      final Instant expiresAt,
      final String requesterSignature,
      final String providerSignature) {
    if (agreementId == null || agreementId.isBlank()) {
      throw new IllegalArgumentException("agreementId must not be blank");
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
    if (createdAt == null || expiresAt == null) {
      throw new IllegalArgumentException("createdAt and expiresAt must not be null");
    }
    if (!expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("expiresAt must be after createdAt");
    }
    if (requesterSignature == null || requesterSignature.isBlank()) {
      throw new IllegalArgumentException("requesterSignature must not be blank");
    }

    this.agreementId = agreementId.trim();
    this.requesterNodeId = requesterNodeId.trim();
    this.providerNodeId = providerNodeId.trim();
    this.fragmentId = fragmentId.trim();
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.requesterSignature = requesterSignature.trim();
    this.providerSignature = providerSignature == null ? null : providerSignature.trim();
  }

  /**
   * Returns agreement identifier.
   *
   * @return agreement identifier
   */
  public String agreementId() {
    return agreementId;
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
   * Returns target fragment identifier.
   *
   * @return fragment identifier
   */
  public String fragmentId() {
    return fragmentId;
  }

  /**
   * Returns creation timestamp.
   *
   * @return creation timestamp
   */
  public Instant createdAt() {
    return createdAt;
  }

  /**
   * Returns expiration timestamp.
   *
   * @return expiration timestamp
   */
  public Instant expiresAt() {
    return expiresAt;
  }

  /**
   * Returns requester signature.
   *
   * @return requester signature
   */
  public String requesterSignature() {
    return requesterSignature;
  }

  /**
   * Returns provider signature.
   *
   * @return provider signature or null when not yet confirmed
   */
  public String providerSignature() {
    return providerSignature;
  }

  /**
   * Checks whether the agreement is expired at a specific instant.
   *
   * @param now current instant
   * @return {@code true} if expired
   */
  public boolean isExpired(final Instant now) {
    Objects.requireNonNull(now, "now must not be null");
    return !now.isBefore(expiresAt);
  }

  /**
   * Checks whether both parties have signed the agreement.
   *
   * @return {@code true} if requester and provider signatures are present
   */
  public boolean isFullySigned() {
    return requesterSignature != null
        && !requesterSignature.isBlank()
        && providerSignature != null
        && !providerSignature.isBlank();
  }

  /**
   * Returns a copy confirmed with provider signature.
   *
   * @param signature provider signature
   * @return confirmed agreement copy
   */
  public StorageAgreement confirmByProvider(final String signature) {
    if (signature == null || signature.isBlank()) {
      throw new IllegalArgumentException("provider signature must not be blank");
    }
    return new StorageAgreement(
        agreementId,
        requesterNodeId,
        providerNodeId,
        fragmentId,
        createdAt,
        expiresAt,
        requesterSignature,
        signature);
  }

  /**
   * Compares this agreement with another object.
   *
   * @param o object to compare
   * @return {@code true} when both agreements are equal
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof StorageAgreement that)) {
      return false;
    }
    return Objects.equals(agreementId, that.agreementId)
        && Objects.equals(requesterNodeId, that.requesterNodeId)
        && Objects.equals(providerNodeId, that.providerNodeId)
        && Objects.equals(fragmentId, that.fragmentId)
        && Objects.equals(createdAt, that.createdAt)
        && Objects.equals(expiresAt, that.expiresAt)
        && Objects.equals(requesterSignature, that.requesterSignature)
        && Objects.equals(providerSignature, that.providerSignature);
  }

  /**
   * Returns hash code for this agreement.
   *
   * @return hash code
   */
  @Override
  public int hashCode() {
    return Objects.hash(
        agreementId,
        requesterNodeId,
        providerNodeId,
        fragmentId,
        createdAt,
        expiresAt,
        requesterSignature,
        providerSignature);
  }
}
