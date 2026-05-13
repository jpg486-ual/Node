package es.ual.node.userregistration.adapters.in.web;

import es.ual.node.userregistration.application.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;

/** Request context helper for authenticated user extraction. */
public final class AuthenticatedUserRequestContext {

  private AuthenticatedUserRequestContext() {}

  /**
   * Returns authenticated user from request context.
   *
   * @param request HTTP request
   * @return authenticated user
   */
  public static AuthenticatedUser require(final HttpServletRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    final Object value =
        request.getAttribute(UserSessionAuthenticationInterceptor.AUTHENTICATED_USER_ATTRIBUTE);
    if (!(value instanceof AuthenticatedUser authenticatedUser)) {
      throw new IllegalStateException("Authenticated user is not available in request context");
    }
    return authenticatedUser;
  }
}
