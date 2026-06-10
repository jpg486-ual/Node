package es.ual.node.custodyliveness.domain;

import java.time.Instant;

/**
 * Durable session state for custody liveness interactions.
 *
 * <p>{@code remoteTutorBaseUrl} carries the remote (requester) tutor base URL when known. It is
 * propagated from the originating {@link
 * es.ual.node.negotiation.domain.NegotiationAgreement#requesterTutorBaseUrl()} so that {@code
 * RETURN_TO_TUTOR} escalation contacts the tutor of the lost requester rather than the local
 * custodian's tutor.
 */
public record CustodyProbeSession(
    String sessionId,
    String remoteNodeId,
    CustodyProbeDirection direction,
    CustodyProbeStatus status,
    int attemptCount,
    Instant lastSuccessAt,
    Instant lastAttemptAt,
    Instant nextAttemptAt,
    String lastError,
    Instant reverseProbeCooldownUntil,
    Instant createdAt,
    Instant updatedAt,
    String remoteTutorBaseUrl) {

  /**
   * Session-id prefix for the per-requester <strong>origin tutor hint</strong>: an inert session
   * row (never {@code next_attempt_at}-due, so probe workers ignore it) that caches the tutor base
   * URL advertised by an origin in its keep-list response. {@code RETURN_TO_TUTOR} escalation reads
   * it to contact the lost requester's own tutor in multi-tutor topologies. The id is {@code
   * "origin-tutor::" + requesterNodeId}.
   */
  public static final String ORIGIN_TUTOR_HINT_PREFIX = "origin-tutor::";

  /**
   * Convenience factory for sessions without a known remote tutor (legacy / reverse probes).
   *
   * @return new session with {@code remoteTutorBaseUrl=null}
   */
  public static CustodyProbeSession withoutRemoteTutor(
      final String sessionId,
      final String remoteNodeId,
      final CustodyProbeDirection direction,
      final CustodyProbeStatus status,
      final int attemptCount,
      final Instant lastSuccessAt,
      final Instant lastAttemptAt,
      final Instant nextAttemptAt,
      final String lastError,
      final Instant reverseProbeCooldownUntil,
      final Instant createdAt,
      final Instant updatedAt) {
    return new CustodyProbeSession(
        sessionId,
        remoteNodeId,
        direction,
        status,
        attemptCount,
        lastSuccessAt,
        lastAttemptAt,
        nextAttemptAt,
        lastError,
        reverseProbeCooldownUntil,
        createdAt,
        updatedAt,
        null);
  }
}
