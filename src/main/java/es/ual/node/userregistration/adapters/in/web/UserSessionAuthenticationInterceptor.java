package es.ual.node.userregistration.adapters.in.web;

import es.ual.node.bootstrap.observability.RequestCorrelationInterceptor;
import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.userregistration.application.AuthenticatedUser;
import es.ual.node.userregistration.application.UserRegistrationService;
import es.ual.node.userregistration.domain.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

/** Interceptor that authenticates client-session bearer tokens. */
public class UserSessionAuthenticationInterceptor implements HandlerInterceptor {

  /** Request attribute key carrying authenticated user context. */
  public static final String AUTHENTICATED_USER_ATTRIBUTE = "authenticatedUser";

  private final UserRegistrationService userRegistrationService;

  /**
   * Creates interceptor.
   *
   * @param userRegistrationService registration/auth service
   */
  public UserSessionAuthenticationInterceptor(
      final UserRegistrationService userRegistrationService) {
    if (userRegistrationService == null) {
      throw new IllegalArgumentException("userRegistrationService must not be null");
    }
    this.userRegistrationService = userRegistrationService;
  }

  @Override
  public boolean preHandle(
      @NonNull final HttpServletRequest request,
      @NonNull final HttpServletResponse response,
      @NonNull final Object handler)
      throws Exception {
    try {
      if (isSignedOpsRequest(request)) {
        final String nodeId = request.getHeader(RequestSignatureValidator.HEADER_NODE_ID);
        final String nonce = request.getHeader(RequestSignatureValidator.HEADER_NONCE);
        final String timestamp = request.getHeader(RequestSignatureValidator.HEADER_TIMESTAMP);
        final String syntheticSessionSeed =
            String.format("signed:%s:%s:%s", nodeId, nonce, timestamp);
        final AuthenticatedUser authenticatedUser =
            new AuthenticatedUser("signed-node:" + nodeId, 0, UserRole.SUPERNODE_ADMIN);
        request.setAttribute(AUTHENTICATED_USER_ATTRIBUTE, authenticatedUser);
        MDC.put(
            RequestCorrelationInterceptor.MDC_SESSION_ID_KEY,
            deriveSessionCorrelationId(syntheticSessionSeed));
        MDC.put(RequestCorrelationInterceptor.MDC_USERNAME_KEY, authenticatedUser.username());
        MDC.put(RequestCorrelationInterceptor.MDC_USER_ROLE_KEY, authenticatedUser.role().name());
        return true;
      }

      final String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
      final String token = extractBearerToken(authorizationHeader);
      final AuthenticatedUser authenticatedUser =
          userRegistrationService.authenticateByToken(token);
      request.setAttribute(AUTHENTICATED_USER_ATTRIBUTE, authenticatedUser);
      MDC.put(RequestCorrelationInterceptor.MDC_SESSION_ID_KEY, deriveSessionCorrelationId(token));
      MDC.put(RequestCorrelationInterceptor.MDC_USERNAME_KEY, authenticatedUser.username());
      MDC.put(RequestCorrelationInterceptor.MDC_USER_ROLE_KEY, authenticatedUser.role().name());
      return true;
    } catch (IllegalArgumentException ex) {
      clearSessionMdc();
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired session");
      return false;
    }
  }

  @Override
  public void afterCompletion(
      @NonNull final HttpServletRequest request,
      @NonNull final HttpServletResponse response,
      @NonNull final Object handler,
      final Exception ex) {
    clearSessionMdc();
  }

  private String extractBearerToken(final String authorizationHeader) {
    if (authorizationHeader == null || authorizationHeader.isBlank()) {
      throw new IllegalArgumentException("Authorization header is required");
    }
    final String prefix = "Bearer ";
    if (!authorizationHeader.startsWith(prefix)
        || authorizationHeader.length() <= prefix.length()) {
      throw new IllegalArgumentException("Authorization must be a Bearer token");
    }
    return authorizationHeader.substring(prefix.length()).trim();
  }

  private static void clearSessionMdc() {
    MDC.remove(RequestCorrelationInterceptor.MDC_SESSION_ID_KEY);
    MDC.remove(RequestCorrelationInterceptor.MDC_USERNAME_KEY);
    MDC.remove(RequestCorrelationInterceptor.MDC_USER_ROLE_KEY);
  }

  private String deriveSessionCorrelationId(final String token) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
      final StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte value : hash) {
        hex.append(String.format("%02x", value));
      }
      return "sess-" + hex.substring(0, 24);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
    }
  }

  private boolean isSignedOpsRequest(final HttpServletRequest request) {
    final String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
    if (!uri.startsWith("/ops/")) {
      return false;
    }
    return hasText(request.getHeader(RequestSignatureValidator.HEADER_NODE_ID))
        && hasText(request.getHeader(RequestSignatureValidator.HEADER_NONCE))
        && hasText(request.getHeader(RequestSignatureValidator.HEADER_TIMESTAMP))
        && hasText(request.getHeader(RequestSignatureValidator.HEADER_SIGNATURE));
  }

  private boolean hasText(final String value) {
    return value != null && !value.isBlank();
  }
}
