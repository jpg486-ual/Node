package es.ual.node.bootstrap.observability;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

/** Interceptor that ensures per-request correlation context is always available. */
public class RequestCorrelationInterceptor implements HandlerInterceptor {

  /** Header used to propagate request correlation id across services. */
  public static final String HEADER_REQUEST_ID = "X-Request-Id";

  /** Request attribute key carrying resolved request correlation id. */
  public static final String REQUEST_ID_ATTRIBUTE = "requestId";

  /** MDC key used for request correlation id. */
  public static final String MDC_REQUEST_ID_KEY = "requestId";

  /** MDC key used for request method. */
  public static final String MDC_HTTP_METHOD_KEY = "httpMethod";

  /** MDC key used for request path. */
  public static final String MDC_HTTP_PATH_KEY = "httpPath";

  /** MDC key used for session correlation id. */
  public static final String MDC_SESSION_ID_KEY = "sessionId";

  /** MDC key used for authenticated username. */
  public static final String MDC_USERNAME_KEY = "username";

  /** MDC key used for authenticated role. */
  public static final String MDC_USER_ROLE_KEY = "userRole";

  /** MDC key used for decision correlation id. */
  public static final String MDC_DECISION_ID_KEY = "decisionId";

  private static final int MAX_REQUEST_ID_LENGTH = 128;

  @Override
  public boolean preHandle(
      @NonNull final HttpServletRequest request,
      @NonNull final HttpServletResponse response,
      @NonNull final Object handler) {
    final String requestId = resolveOrGenerateRequestId(request.getHeader(HEADER_REQUEST_ID));
    request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
    response.setHeader(HEADER_REQUEST_ID, requestId);

    MDC.put(MDC_REQUEST_ID_KEY, requestId);
    MDC.put(MDC_HTTP_METHOD_KEY, request.getMethod());
    final String path = request.getRequestURI();
    MDC.put(MDC_HTTP_PATH_KEY, path == null ? "" : path);
    return true;
  }

  @Override
  public void afterCompletion(
      @NonNull final HttpServletRequest request,
      @NonNull final HttpServletResponse response,
      @NonNull final Object handler,
      final Exception ex) {
    MDC.remove(MDC_REQUEST_ID_KEY);
    MDC.remove(MDC_HTTP_METHOD_KEY);
    MDC.remove(MDC_HTTP_PATH_KEY);
    MDC.remove(MDC_DECISION_ID_KEY);
  }

  /** Resolves request id from request attribute/header when available. */
  public static String resolveRequestId(final HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    final Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
    if (value instanceof String requestId && !requestId.isBlank()) {
      return requestId;
    }
    return normalizeRequestId(request.getHeader(HEADER_REQUEST_ID));
  }

  private String resolveOrGenerateRequestId(final String providedHeader) {
    final String normalized = normalizeRequestId(providedHeader);
    if (normalized != null) {
      return normalized;
    }
    return UUID.randomUUID().toString();
  }

  private static String normalizeRequestId(final String requestIdHeader) {
    if (requestIdHeader == null || requestIdHeader.isBlank()) {
      return null;
    }
    final StringBuilder sanitized = new StringBuilder(MAX_REQUEST_ID_LENGTH);
    final String trimmed = requestIdHeader.trim();
    for (int i = 0; i < trimmed.length() && sanitized.length() < MAX_REQUEST_ID_LENGTH; i++) {
      final char current = trimmed.charAt(i);
      if ((current >= 'a' && current <= 'z')
          || (current >= 'A' && current <= 'Z')
          || (current >= '0' && current <= '9')
          || current == '.'
          || current == '_'
          || current == '-') {
        sanitized.append(current);
      }
    }
    if (sanitized.isEmpty()) {
      return null;
    }
    return sanitized.toString();
  }
}
