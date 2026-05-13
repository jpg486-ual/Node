package es.ual.node.filesystem.adapters.in.web;

import java.time.Instant;

/**
 * 413 error payload for {@code CONTENT_TOO_LARGE}. Carries the offending size and the configured
 * maximum so the client can adjust the request without round-trips.
 */
public record FileContentTooLargeErrorPayload(
    String errorCode, String message, long sizeBytes, long maxBytes, Instant timestamp) {

  public static FileContentTooLargeErrorPayload of(
      final String errorCode, final String message, final long sizeBytes, final long maxBytes) {
    return new FileContentTooLargeErrorPayload(
        errorCode, message, sizeBytes, maxBytes, Instant.now());
  }
}
