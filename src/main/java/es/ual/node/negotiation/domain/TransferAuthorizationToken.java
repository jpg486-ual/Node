package es.ual.node.negotiation.domain;

import java.time.Instant;

/** Immutable token authorizing the transfer phase after confirmation. */
public final class TransferAuthorizationToken {

  private final String token;
  private final String agreementId;
  private final Instant issuedAt;
  private final Instant expiresAt;

  /**
   * Creates transfer authorization token.
   *
   * @param token token value
   * @param agreementId agreement identifier
   * @param issuedAt issued timestamp
   * @param expiresAt expiration timestamp
   */
  public TransferAuthorizationToken(
      final String token,
      final String agreementId,
      final Instant issuedAt,
      final Instant expiresAt) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("token must not be blank");
    }
    if (agreementId == null || agreementId.isBlank()) {
      throw new IllegalArgumentException("agreementId must not be blank");
    }
    if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
      throw new IllegalArgumentException("invalid token timestamps");
    }
    this.token = token.trim();
    this.agreementId = agreementId.trim();
    this.issuedAt = issuedAt;
    this.expiresAt = expiresAt;
  }

  /**
   * Returns token value.
   *
   * @return token value
   */
  public String token() {
    return token;
  }

  /**
   * Returns agreement id.
   *
   * @return agreement id
   */
  public String agreementId() {
    return agreementId;
  }

  /**
   * Returns issued-at timestamp.
   *
   * @return issued-at timestamp
   */
  public Instant issuedAt() {
    return issuedAt;
  }

  /**
   * Returns expiry timestamp.
   *
   * @return expiry timestamp
   */
  public Instant expiresAt() {
    return expiresAt;
  }
}
