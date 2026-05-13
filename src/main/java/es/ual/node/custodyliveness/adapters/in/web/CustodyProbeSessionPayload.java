package es.ual.node.custodyliveness.adapters.in.web;

import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import java.time.Instant;

/** HTTP payload for custody liveness session state. */
public final class CustodyProbeSessionPayload {

  private String sessionId;
  private String remoteNodeId;
  private String direction;
  private String status;
  private int attemptCount;
  private Instant lastSuccessAt;
  private Instant lastAttemptAt;
  private Instant nextAttemptAt;
  private String lastError;
  private Instant updatedAt;

  public static CustodyProbeSessionPayload fromDomain(final CustodyProbeSession session) {
    final CustodyProbeSessionPayload payload = new CustodyProbeSessionPayload();
    payload.sessionId = session.sessionId();
    payload.remoteNodeId = session.remoteNodeId();
    payload.direction = session.direction().name();
    payload.status = session.status().name();
    payload.attemptCount = session.attemptCount();
    payload.lastSuccessAt = session.lastSuccessAt();
    payload.lastAttemptAt = session.lastAttemptAt();
    payload.nextAttemptAt = session.nextAttemptAt();
    payload.lastError = session.lastError();
    payload.updatedAt = session.updatedAt();
    return payload;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getRemoteNodeId() {
    return remoteNodeId;
  }

  public String getDirection() {
    return direction;
  }

  public String getStatus() {
    return status;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public Instant getLastSuccessAt() {
    return lastSuccessAt;
  }

  public Instant getLastAttemptAt() {
    return lastAttemptAt;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public String getLastError() {
    return lastError;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
