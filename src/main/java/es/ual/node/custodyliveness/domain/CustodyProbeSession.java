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
