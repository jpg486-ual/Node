package es.ual.node.userregistration.adapters.in.web;

import java.time.Instant;

/** Standard API error payload for client-facing auth endpoints. */
public record ApiErrorPayload(String errorCode, String message, Instant timestamp) {

  /**
   * Creates payload with current timestamp.
   *
   * @param errorCode stable error code
   * @param message human-readable message
   * @return payload instance
   */
  public static ApiErrorPayload of(final String errorCode, final String message) {
    return new ApiErrorPayload(errorCode, message, Instant.now());
  }
}
