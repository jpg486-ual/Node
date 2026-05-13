package es.ual.node.userregistration.domain;

import java.util.Locale;

/** Base RBAC roles for client-authenticated users. */
public enum UserRole {
  END_USER,
  NODE_ADMIN,
  SUPERNODE_ADMIN;

  /**
   * Parses role name using case-insensitive matching.
   *
   * @param raw raw value
   * @return parsed role
   */
  public static UserRole parse(final String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("role must not be blank");
    }
    final String normalized = raw.trim().toUpperCase(Locale.ROOT);
    try {
      return UserRole.valueOf(normalized);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unsupported role: " + raw);
    }
  }
}
