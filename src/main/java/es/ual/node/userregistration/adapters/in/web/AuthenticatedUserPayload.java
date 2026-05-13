package es.ual.node.userregistration.adapters.in.web;

import es.ual.node.userregistration.application.AuthenticatedUser;
import es.ual.node.userregistration.domain.UserRole;

/**
 * Authenticated user response payload. {@code quotaUsedBytes} is the live consumption tracked by
 * {@link es.ual.node.userregistration.ports.out.UserQuotaPort}. It includes the Reed-Solomon
 * overhead {@code (size × n / k)} for every distributed file the user owns. The client uses {@code
 * quotaUsedBytes} together with {@code quotaMb × 1 048 576} to render a percentage and warn the
 * user before an upload exceeds the allowance.
 */
public record AuthenticatedUserPayload(
    String username, int quotaMb, long quotaUsedBytes, UserRole role) {

  /**
   * Maps application model to payload, defaulting {@code quotaUsedBytes} to zero when the live
   * counter is unavailable (legacy callers or constructors that don't yet propagate it).
   *
   * @param authenticatedUser authenticated user
   * @return payload with {@code quotaUsedBytes = 0}
   */
  public static AuthenticatedUserPayload fromApplication(
      final AuthenticatedUser authenticatedUser) {
    return fromApplication(authenticatedUser, 0L);
  }

  /**
   * Maps application model to payload, surfacing the live {@code quotaUsedBytes}.
   *
   * @param authenticatedUser authenticated user
   * @param quotaUsedBytes live consumption (RS-inflated bytes the user occupies cluster-wide)
   * @return payload
   */
  public static AuthenticatedUserPayload fromApplication(
      final AuthenticatedUser authenticatedUser, final long quotaUsedBytes) {
    return new AuthenticatedUserPayload(
        authenticatedUser.username(),
        authenticatedUser.quotaMb(),
        quotaUsedBytes,
        authenticatedUser.role());
  }
}
