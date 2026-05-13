package es.ual.node.userregistration.ports.out;

import es.ual.node.userregistration.domain.UserAccount;
import java.util.Optional;

/** User account persistence boundary. */
public interface UserAccountPort {

  /**
   * Returns whether username already exists.
   *
   * @param username username
   * @return true when account exists
   */
  boolean existsByUsername(String username);

  /**
   * Persists account.
   *
   * @param userAccount user account
   */
  void save(UserAccount userAccount);

  /**
   * Finds account by username.
   *
   * @param username username
   * @return optional account
   */
  Optional<UserAccount> findByUsername(String username);
}
