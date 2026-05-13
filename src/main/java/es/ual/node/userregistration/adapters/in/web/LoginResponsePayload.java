package es.ual.node.userregistration.adapters.in.web;

import es.ual.node.userregistration.application.AuthenticatedSession;
import es.ual.node.userregistration.domain.UserRole;
import java.time.Instant;

/** Login endpoint response payload. */
public record LoginResponsePayload(
    String token, String username, int quotaMb, UserRole role, Instant expiresAt) {

  /**
   * Maps application session to response payload.
   *
   * @param session authenticated session
   * @return payload
   */
  public static LoginResponsePayload fromApplication(final AuthenticatedSession session) {
    return new LoginResponsePayload(
        session.token(),
        session.username(),
        session.quotaMb(),
        session.role(),
        session.expiresAt());
  }
}
