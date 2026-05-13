package es.ual.node.custodyliveness.application;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Scheduled worker that picks expired custody fragments and dispatches them to the {@code
 * applyEscalation} flow (RETURN_TO_TUTOR or defer-and-warn).
 *
 * <p>La caducidad TTL natural dispara la transición legítima:
 *
 * <ul>
 *   <li>Tutor del requester reachable → fragment migra a {@code recovery_orphan_fragment} via POST
 *       /recovery/fragments + delete custody local.
 *   <li>Tutor unreachable → {@code deferEscalationAndRenewTtl} extiende TTL +N + emite {@code
 *       ESCALATION_DEFERRED_TUTOR_DOWN} sostenido.
 * </ul>
 *
 * <p>Default delay 60s, alineado con {@code CustodianProbeWorker}.
 */
public class CustodyExpiryEscalationWorker {

  private final CustodyLivenessService service;
  private final ObservationRegistry observationRegistry;

  /** Convenience ctor (NOOP observation registry). */
  public CustodyExpiryEscalationWorker(final CustodyLivenessService service) {
    this(service, ObservationRegistry.NOOP);
  }

  /** Full ctor with tracing. */
  public CustodyExpiryEscalationWorker(
      final CustodyLivenessService service, final ObservationRegistry observationRegistry) {
    if (service == null || observationRegistry == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.service = service;
    this.observationRegistry = observationRegistry;
  }

  /** Processes the next batch of expired custody fragments. */
  @Scheduled(
      fixedDelayString = "${node.custody-liveness.expiry-escalation-fixed-delay-millis:60000}")
  public void runCycle() {
    Observation.createNotStarted("node.custody.expiry.escalate", observationRegistry)
        .observe(service::escalateExpiredCustodyFragments);
  }
}
