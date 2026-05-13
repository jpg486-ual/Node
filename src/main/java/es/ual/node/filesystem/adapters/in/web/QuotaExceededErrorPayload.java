package es.ual.node.filesystem.adapters.in.web;

import java.time.Instant;

/**
 * 413 error payload for {@code QUOTA_EXCEEDED} Carries {@code requested} (RS-inflated bytes the
 * upload would consume) and {@code available} (remaining quota) so the client can show a clear "no
 * fits" message and adjust the upload size accordingly.
 */
public record QuotaExceededErrorPayload(
    String errorCode, String message, long requestedBytes, long availableBytes, Instant timestamp) {

  public static QuotaExceededErrorPayload of(
      final String errorCode,
      final String message,
      final long requestedBytes,
      final long availableBytes) {
    return new QuotaExceededErrorPayload(
        errorCode, message, requestedBytes, availableBytes, Instant.now());
  }
}
