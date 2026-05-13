package es.ual.node.userregistration.domain;

import java.time.Instant;

/** Immutable user session model. */
public record UserSession(
    String token, String username, Instant issuedAt, Instant expiresAt, boolean revoked) {

  /**
   * Creates validated session.
   *
   * @param token session token
   * @param username username
   * @param issuedAt issue instant
   * @param expiresAt expiration instant
   * @param revoked revocation flag
   */
  public UserSession {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("token must not be blank");
    }
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    if (issuedAt == null || expiresAt == null) {
      throw new IllegalArgumentException("issuedAt and expiresAt must not be null");
    }
    if (!expiresAt.isAfter(issuedAt)) {
      throw new IllegalArgumentException("expiresAt must be after issuedAt");
    }
    token = token.trim();
    username = username.trim();
  }
}
