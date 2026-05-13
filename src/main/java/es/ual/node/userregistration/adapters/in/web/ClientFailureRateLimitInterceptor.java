package es.ual.node.userregistration.adapters.in.web;

import es.ual.node.userregistration.application.ClientFailureRateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

/** Optional interceptor that temporarily blocks clients producing repeated failures. */
public class ClientFailureRateLimitInterceptor implements HandlerInterceptor {

  private static final String REQUEST_KEY_ATTRIBUTE = "clientFailureRateLimitKey";

  private final ClientFailureRateLimitProperties properties;
  private final Map<String, FailureTracker> trackers = new ConcurrentHashMap<>();

  /**
   * Creates interceptor.
   *
   * @param properties limiter properties
   */
  public ClientFailureRateLimitInterceptor(final ClientFailureRateLimitProperties properties) {
    if (properties == null) {
      throw new IllegalArgumentException("properties must not be null");
    }
    this.properties = properties;
  }

  @Override
  public boolean preHandle(
      @NonNull final HttpServletRequest request,
      @NonNull final HttpServletResponse response,
      @NonNull final Object handler)
      throws Exception {
    if (!properties.isEnabled()) {
      return true;
    }

    final String key = resolveClientKey(request);
    request.setAttribute(REQUEST_KEY_ATTRIBUTE, key);
    final long now = System.currentTimeMillis();
    final FailureTracker tracker =
        trackers.computeIfAbsent(key, ignored -> new FailureTracker(now));

    synchronized (tracker) {
      tracker.rotateWindowIfNeeded(now, properties.getWindowSeconds() * 1000L);
      if (tracker.blockedUntilMillis > now) {
        final long retryAfterSeconds =
            Math.max(1L, (tracker.blockedUntilMillis - now + 999L) / 1000L);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.sendError(429, "Too many failed requests. Retry later.");
        return false;
      }
    }

    return true;
  }

  @Override
  public void afterCompletion(
      @NonNull final HttpServletRequest request,
      @NonNull final HttpServletResponse response,
      @NonNull final Object handler,
      final Exception ex) {
    if (!properties.isEnabled()) {
      return;
    }

    final int status = response.getStatus();
    final boolean isClientFailure = status >= 400 && status <= 499;
    final boolean isServerFailure = properties.isCountServerErrors() && status >= 500;
    if (!isClientFailure && !isServerFailure) {
      return;
    }

    final Object keyAttr = request.getAttribute(REQUEST_KEY_ATTRIBUTE);
    final String key = keyAttr instanceof String ? (String) keyAttr : resolveClientKey(request);
    final long now = System.currentTimeMillis();
    final FailureTracker tracker =
        trackers.computeIfAbsent(key, ignored -> new FailureTracker(now));

    synchronized (tracker) {
      tracker.rotateWindowIfNeeded(now, properties.getWindowSeconds() * 1000L);
      tracker.failures++;
      if (tracker.failures >= properties.getMaxFailures()) {
        tracker.failures = 0;
        tracker.windowStartMillis = now;
        tracker.blockedUntilMillis = now + (properties.getBlockSeconds() * 1000L);
      }
    }
  }

  private String resolveClientKey(final HttpServletRequest request) {
    final String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    final String prefix = "Bearer ";
    if (authorization != null
        && authorization.startsWith(prefix)
        && authorization.length() > prefix.length()) {
      return "token:" + authorization.substring(prefix.length()).trim();
    }
    final String remoteAddr = request.getRemoteAddr();
    return "ip:" + (remoteAddr == null ? "unknown" : remoteAddr);
  }

  private static final class FailureTracker {
    private long windowStartMillis;
    private int failures;
    private long blockedUntilMillis;

    private FailureTracker(final long now) {
      this.windowStartMillis = now;
      this.failures = 0;
      this.blockedUntilMillis = 0L;
    }

    private void rotateWindowIfNeeded(final long now, final long windowMillis) {
      if (now - windowStartMillis >= windowMillis) {
        windowStartMillis = now;
        failures = 0;
      }
    }
  }
}
