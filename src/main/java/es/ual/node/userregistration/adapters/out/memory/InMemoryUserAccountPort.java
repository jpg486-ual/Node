package es.ual.node.userregistration.adapters.out.memory;

import es.ual.node.userregistration.domain.UserAccount;
import es.ual.node.userregistration.ports.out.UserAccountPort;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory user account adapter. */
public class InMemoryUserAccountPort implements UserAccountPort {

  private final Map<String, UserAccount> accounts = new ConcurrentHashMap<>();

  @Override
  public boolean existsByUsername(final String username) {
    return accounts.containsKey(normalize(username));
  }

  @Override
  public void save(final UserAccount userAccount) {
    if (userAccount == null) {
      throw new IllegalArgumentException("userAccount must not be null");
    }
    accounts.put(normalize(userAccount.username()), userAccount);
  }

  @Override
  public Optional<UserAccount> findByUsername(final String username) {
    return Optional.ofNullable(accounts.get(normalize(username)));
  }

  private String normalize(final String username) {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    return username.trim().toLowerCase();
  }
}
