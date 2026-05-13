package es.ual.node.custodyliveness.application;

import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import es.ual.node.custodyliveness.domain.CustodyProbeStatus;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks operational counters for custody liveness workflows. */
public class CustodyLivenessObservabilityService {

  private final boolean enabled;
  private final AtomicLong probesInboundTotal;
  private final AtomicLong probesOutboundScheduledTotal;
  private final AtomicLong probesOutboundDeduplicatedTotal;
  private final AtomicLong probesOutboundSuccessTotal;
  private final AtomicLong probesOutboundFailureTotal;
  private final AtomicLong transitionsActiveTotal;
  private final AtomicLong transitionsSuspectTotal;
  private final AtomicLong transitionsUnresponsiveTotal;
  private final AtomicLong transitionsEscalatedTotal;
  // Cuenta cada vez que el escalation RETURN_TO_TUTOR difiere por tutor caído.
  private final AtomicLong escalationDeferredTotal;
  // Cuenta fragments procesados por el worker de TTL-expiry escalation (sin probe activo,
  // típicamente cluster cold-restart u origen permanently unreachable).
  private final AtomicLong expiryEscalationTotal;

  /** Creates enabled observability service. */
  public CustodyLivenessObservabilityService() {
    this(true);
  }

  private CustodyLivenessObservabilityService(final boolean enabled) {
    this.enabled = enabled;
    this.probesInboundTotal = new AtomicLong();
    this.probesOutboundScheduledTotal = new AtomicLong();
    this.probesOutboundDeduplicatedTotal = new AtomicLong();
    this.probesOutboundSuccessTotal = new AtomicLong();
    this.probesOutboundFailureTotal = new AtomicLong();
    this.transitionsActiveTotal = new AtomicLong();
    this.transitionsSuspectTotal = new AtomicLong();
    this.transitionsUnresponsiveTotal = new AtomicLong();
    this.transitionsEscalatedTotal = new AtomicLong();
    this.escalationDeferredTotal = new AtomicLong();
    this.expiryEscalationTotal = new AtomicLong();
  }

  /**
   * Creates a no-op instance for tests not wiring metrics.
   *
   * @return no-op service
   */
  public static CustodyLivenessObservabilityService noop() {
    return new CustodyLivenessObservabilityService(false);
  }

  /** Records one inbound probe handling. */
  public void onInboundProbeHandled() {
    if (!enabled) {
      return;
    }
    probesInboundTotal.incrementAndGet();
  }

  /**
   * Records one outbound scheduling action.
   *
   * @param deduplicated true when an existing session was reused
   */
  public void onOutboundSessionScheduled(final boolean deduplicated) {
    if (!enabled) {
      return;
    }
    probesOutboundScheduledTotal.incrementAndGet();
    if (deduplicated) {
      probesOutboundDeduplicatedTotal.incrementAndGet();
    }
  }

  /** Records one successful outbound probe execution. */
  public void onOutboundProbeSuccess() {
    if (!enabled) {
      return;
    }
    probesOutboundSuccessTotal.incrementAndGet();
  }

  /** Records one failed outbound probe execution. */
  public void onOutboundProbeFailure() {
    if (!enabled) {
      return;
    }
    probesOutboundFailureTotal.incrementAndGet();
  }

  /**
   * Records a state transition for one session.
   *
   * @param previous previous session state
   * @param current current session state
   */
  public void onTransition(final CustodyProbeSession previous, final CustodyProbeSession current) {
    if (!enabled || current == null) {
      return;
    }
    final CustodyProbeStatus previousStatus = previous == null ? null : previous.status();
    final CustodyProbeStatus currentStatus = current.status();

    if (currentStatus == CustodyProbeStatus.ACTIVE && previousStatus != CustodyProbeStatus.ACTIVE) {
      transitionsActiveTotal.incrementAndGet();
    }
    if (currentStatus == CustodyProbeStatus.SUSPECT
        && previousStatus != CustodyProbeStatus.SUSPECT) {
      transitionsSuspectTotal.incrementAndGet();
    }
    if (currentStatus == CustodyProbeStatus.UNRESPONSIVE
        && previousStatus != CustodyProbeStatus.UNRESPONSIVE) {
      transitionsUnresponsiveTotal.incrementAndGet();
    }
    if (currentStatus == CustodyProbeStatus.ESCALATED
        && previousStatus != CustodyProbeStatus.ESCALATED) {
      transitionsEscalatedTotal.incrementAndGet();
    }
  }

  /**
   * Records one escalation deferral. Invoked when the POST to tutor fails (timeout, 4xx/5xx not
   * 201/409) and the peer-side renews the custody TTL instead of dropping the fragment. The probe
   * cycle will re-dispatch escalation when the tutor recovers.
   */
  public void onEscalationDeferred() {
    if (!enabled) {
      return;
    }
    escalationDeferredTotal.incrementAndGet();
  }

  /**
   * Records that the expiry escalation worker processed N expired fragments for one requester (TTL
   * caducó sin probe activo). Counter cumulative; suma de varios cycles a lo largo del tiempo
   * refleja la presión sobre el flujo RETURN_TO_TUTOR motivado por inactividad.
   *
   * @param fragmentsCount number of fragments included in the escalation batch
   */
  public void onExpiryEscalation(final int fragmentsCount) {
    if (!enabled || fragmentsCount <= 0) {
      return;
    }
    expiryEscalationTotal.addAndGet(fragmentsCount);
  }

  /**
   * Returns metrics snapshot.
   *
   * @return metrics snapshot
   */
  public CustodyLivenessMetricsSnapshot snapshot() {
    return new CustodyLivenessMetricsSnapshot(
        probesInboundTotal.get(),
        probesOutboundScheduledTotal.get(),
        probesOutboundDeduplicatedTotal.get(),
        probesOutboundSuccessTotal.get(),
        probesOutboundFailureTotal.get(),
        transitionsActiveTotal.get(),
        transitionsSuspectTotal.get(),
        transitionsUnresponsiveTotal.get(),
        transitionsEscalatedTotal.get(),
        escalationDeferredTotal.get(),
        expiryEscalationTotal.get());
  }
}
