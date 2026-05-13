package es.ual.node.userregistration.ports.out;

import es.ual.node.userregistration.domain.UserSession;
import java.util.Optional;

/** User session persistence boundary. */
public interface UserSessionPort {

  /**
   * Persists user session.
   *
   * @param userSession session model
   */
  void save(UserSession userSession);

  /**
   * Finds session by token.
   *
   * @param token session token
   * @return optional session
   */
  Optional<UserSession> findByToken(String token);

  /**
   * Revokes session token.
   *
   * @param token session token
   */
  void revoke(String token);
}
