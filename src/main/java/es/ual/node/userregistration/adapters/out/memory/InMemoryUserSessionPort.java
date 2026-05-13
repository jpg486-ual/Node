package es.ual.node.userregistration.adapters.out.memory;

import es.ual.node.userregistration.domain.UserSession;
import es.ual.node.userregistration.ports.out.UserSessionPort;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory user session adapter. */
public class InMemoryUserSessionPort implements UserSessionPort {

  private final Map<String, UserSession> sessionsByToken = new ConcurrentHashMap<>();

  @Override
  public void save(final UserSession userSession) {
    if (userSession == null) {
      throw new IllegalArgumentException("userSession must not be null");
    }
    sessionsByToken.put(userSession.token(), userSession);
  }

  @Override
  public Optional<UserSession> findByToken(final String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("token must not be blank");
    }
    return Optional.ofNullable(sessionsByToken.get(token.trim()));
  }

  @Override
  public void revoke(final String token) {
    final String normalizedToken = normalizeToken(token);
    final UserSession previous = sessionsByToken.get(normalizedToken);
    if (previous == null) {
      return;
    }
    sessionsByToken.put(
        normalizedToken,
        new UserSession(
            previous.token(),
            previous.username(),
            previous.issuedAt(),
            previous.expiresAt(),
            true));
  }

  private String normalizeToken(final String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("token must not be blank");
    }
    return token.trim();
  }
}
