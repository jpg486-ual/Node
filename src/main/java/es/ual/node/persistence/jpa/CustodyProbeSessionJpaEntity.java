package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA entity for custody liveness probe session state. */
@Entity
@Table(name = "custody_probe_session")
public class CustodyProbeSessionJpaEntity {

  @Id
  @Column(name = "session_id", nullable = false, length = 160)
  private String sessionId;

  @Column(name = "remote_node_id", nullable = false, length = 128)
  private String remoteNodeId;

  @Column(name = "direction", nullable = false, length = 32)
  private String direction;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "last_success_at")
  private Instant lastSuccessAt;

  @Column(name = "last_attempt_at")
  private Instant lastAttemptAt;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "last_error", length = 1024)
  private String lastError;

  @Column(name = "reverse_probe_cooldown_until")
  private Instant reverseProbeCooldownUntil;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "remote_tutor_base_url", length = 256)
  private String remoteTutorBaseUrl;

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(final String sessionId) {
    this.sessionId = sessionId;
  }

  public String getRemoteNodeId() {
    return remoteNodeId;
  }

  public void setRemoteNodeId(final String remoteNodeId) {
    this.remoteNodeId = remoteNodeId;
  }

  public String getDirection() {
    return direction;
  }

  public void setDirection(final String direction) {
    this.direction = direction;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(final String status) {
    this.status = status;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public void setAttemptCount(final int attemptCount) {
    this.attemptCount = attemptCount;
  }

  public Instant getLastSuccessAt() {
    return lastSuccessAt;
  }

  public void setLastSuccessAt(final Instant lastSuccessAt) {
    this.lastSuccessAt = lastSuccessAt;
  }

  public Instant getLastAttemptAt() {
    return lastAttemptAt;
  }

  public void setLastAttemptAt(final Instant lastAttemptAt) {
    this.lastAttemptAt = lastAttemptAt;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public void setNextAttemptAt(final Instant nextAttemptAt) {
    this.nextAttemptAt = nextAttemptAt;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(final String lastError) {
    this.lastError = lastError;
  }

  public Instant getReverseProbeCooldownUntil() {
    return reverseProbeCooldownUntil;
  }

  public void setReverseProbeCooldownUntil(final Instant reverseProbeCooldownUntil) {
    this.reverseProbeCooldownUntil = reverseProbeCooldownUntil;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(final Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(final Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public String getRemoteTutorBaseUrl() {
    return remoteTutorBaseUrl;
  }

  public void setRemoteTutorBaseUrl(final String remoteTutorBaseUrl) {
    this.remoteTutorBaseUrl = remoteTutorBaseUrl;
  }
}
