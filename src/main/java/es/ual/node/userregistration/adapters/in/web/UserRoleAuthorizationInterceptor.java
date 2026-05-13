package es.ual.node.userregistration.adapters.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.userregistration.application.AuthenticatedUser;
import es.ual.node.userregistration.domain.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces base RBAC rules for authenticated requests.
 *
 * <ul>
 *   <li>{@code /ops/**}: superficie inter-node firmada. Requiere {@code NODE_ADMIN} o {@code
 *       SUPERNODE_ADMIN} (sintetizados por {@link UserSessionAuthenticationInterceptor:53} cuando
 *       la firma valida).
 *   <li>{@code /fs|files|sync}: superficie cliente bearer JWT. Requiere {@code END_USER}.
 * </ul>
 *
 * <p>Los enum values {@code UserRole.NODE_ADMIN} y {@code UserRole.SUPERNODE_ADMIN} se preservan
 * porque son <strong>load-bearing</strong> para el flujo signed inter-node: sin ellos, el synthetic
 * context que asigna {@code AuthenticatedUser("signed-node:<nodeId>", role=SUPERNODE_ADMIN)}
 * fallaría y todos los {@code /ops/**} caerían a 403.
 */
public class UserRoleAuthorizationInterceptor implements HandlerInterceptor {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(UserRoleAuthorizationInterceptor.class);

  private final ObjectMapper objectMapper;

  /**
   * Creates RBAC interceptor.
   *
   * @param objectMapper json mapper for stable error payloads
   */
  public UserRoleAuthorizationInterceptor(final ObjectMapper objectMapper) {
    if (objectMapper == null) {
      throw new IllegalArgumentException("objectMapper must not be null");
    }
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean preHandle(
      @NonNull final HttpServletRequest request,
      @NonNull final HttpServletResponse response,
      @NonNull final Object handler)
      throws Exception {
    final AuthenticatedUser authenticatedUser = AuthenticatedUserRequestContext.require(request);
    final UserRole role = authenticatedUser.role();
    final String uri = request.getRequestURI() == null ? "" : request.getRequestURI();

    if (uri.startsWith("/ops/")) {
      if (role == UserRole.NODE_ADMIN || role == UserRole.SUPERNODE_ADMIN) {
        return true;
      }
      return deny(
          response,
          HttpServletResponse.SC_FORBIDDEN,
          "OPS_ACCESS_ROLE_FORBIDDEN",
          "Insufficient role for ops endpoint",
          role,
          uri,
          null);
    }

    if (uri.startsWith("/fs/") || uri.startsWith("/files/") || uri.startsWith("/sync/")) {
      if (role == UserRole.END_USER) {
        return true;
      }
      return deny(
          response,
          HttpServletResponse.SC_FORBIDDEN,
          "CLIENT_ACCESS_ROLE_FORBIDDEN",
          "Insufficient role for client endpoint",
          role,
          uri,
          null);
    }

    return true;
  }

  private boolean deny(
      final HttpServletResponse response,
      final int status,
      final String errorCode,
      final String message,
      final UserRole role,
      final String uri,
      final String scope)
      throws IOException {
    LOGGER
        .atWarn()
        .setMessage("Role authorization rejected request")
        .addKeyValue("path", uri)
        .addKeyValue("role", role == null ? "unknown" : role.name())
        .addKeyValue("scope", scope == null ? "n/a" : scope)
        .addKeyValue("errorCode", errorCode)
        .log();

    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write(objectMapper.writeValueAsString(ApiErrorPayload.of(errorCode, message)));
    return false;
  }
}
