package es.ual.node.bootstrap.observability;

import es.ual.node.custodyliveness.application.CustodyLivenessObservabilityService;
import es.ual.node.discovery.application.DiscoveryObservabilityService;
import es.ual.node.recovery.application.RecoveryObservabilityService;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Dual-emit bridge: expone las tres familias de métricas operativas (Discovery, Custody-Liveness,
 * Recovery) como meters Micrometer leídos por {@code /actuator/prometheus} sin modificar los
 * services existentes ni los endpoints JSON {@code /ops/**\/metrics}.
 *
 * <p>El patrón es {@link FunctionCounter} / {@link Gauge} con supplier que llama {@code
 * service.snapshot().campo()} en cada scrape, así el valor de cada meter es una lectura atómica
 * sobre la misma fuente de verdad que el JSON y nunca puede divergir.
 *
 * <p>Cada service se inyecta vía {@link ObjectProvider}: sólo se registran los meters de la familia
 * cuyo feature flag esté activo (Discovery y Delivery están activos por defecto; Recovery y
 * Custody-Liveness sólo cuando {@code node.features.recovery-enabled} y {@code
 * node.custody-liveness.enabled} se activan). Respeta la política project-wide "servicios detrás de
 * feature flag usan ObjectProvider".
 */
@Component
public class PrometheusMetricsBridge {

  private final MeterRegistry registry;
  private final ObjectProvider<DiscoveryObservabilityService> discoveryProvider;
  private final ObjectProvider<CustodyLivenessObservabilityService> custodyLivenessProvider;
  private final ObjectProvider<RecoveryObservabilityService> recoveryProvider;

  /** Creates bridge. */
  public PrometheusMetricsBridge(
      final MeterRegistry registry,
      final ObjectProvider<DiscoveryObservabilityService> discoveryProvider,
      final ObjectProvider<CustodyLivenessObservabilityService> custodyLivenessProvider,
      final ObjectProvider<RecoveryObservabilityService> recoveryProvider) {
    if (registry == null) {
      throw new IllegalArgumentException("registry must not be null");
    }
    this.registry = registry;
    this.discoveryProvider = discoveryProvider;
    this.custodyLivenessProvider = custodyLivenessProvider;
    this.recoveryProvider = recoveryProvider;
  }

  /** Registers all meters when the Spring context is fully initialised. */
  @PostConstruct
  public void registerMeters() {
    registerDiscoveryMeters();
    registerCustodyLivenessMeters();
    registerRecoveryMeters();
  }

  private void registerDiscoveryMeters() {
    final DiscoveryObservabilityService service =
        discoveryProvider == null ? null : discoveryProvider.getIfAvailable();
    if (service == null) {
      return;
    }
    Gauge.builder(
            "node_discovery_queue_pending", service, s -> (double) s.snapshot().pendingQueueCount())
        .description("Discovery retry queue items currently in PENDING state")
        .register(registry);
    Gauge.builder(
            "node_discovery_queue_resolved",
            service,
            s -> (double) s.snapshot().resolvedQueueCount())
        .description("Discovery retry queue items currently in RESOLVED state")
        .register(registry);
    Gauge.builder(
            "node_discovery_queue_failed", service, s -> (double) s.snapshot().failedQueueCount())
        .description("Discovery retry queue items currently in FAILED state")
        .register(registry);
    Gauge.builder(
            "node_discovery_candidates_active",
            service,
            s -> (double) s.snapshot().activeCandidatesCount())
        .description("Discovery candidate directory entries currently marked active")
        .register(registry);
  }

  private void registerCustodyLivenessMeters() {
    final CustodyLivenessObservabilityService service =
        custodyLivenessProvider == null ? null : custodyLivenessProvider.getIfAvailable();
    if (service == null) {
      return;
    }
    counter(
        "node_custody_liveness_inbound_total",
        service,
        s -> s.snapshot().probesInboundTotal(),
        "Custody-liveness inbound probes handled (cumulative)");
    counter(
        "node_custody_liveness_outbound_scheduled_total",
        service,
        s -> s.snapshot().probesOutboundScheduledTotal(),
        "Custody-liveness outbound probes scheduled (cumulative)");
    counter(
        "node_custody_liveness_outbound_deduplicated_total",
        service,
        s -> s.snapshot().probesOutboundDeduplicatedTotal(),
        "Custody-liveness outbound probes deduplicated (cumulative)");
    counter(
        "node_custody_liveness_outbound_success_total",
        service,
        s -> s.snapshot().probesOutboundSuccessTotal(),
        "Custody-liveness outbound probes with success outcome (cumulative)");
    counter(
        "node_custody_liveness_outbound_failure_total",
        service,
        s -> s.snapshot().probesOutboundFailureTotal(),
        "Custody-liveness outbound probes with failure outcome (cumulative)");
    counter(
        "node_custody_liveness_transition_active_total",
        service,
        s -> s.snapshot().transitionsActiveTotal(),
        "Custody-liveness transitions into ACTIVE (cumulative)");
    counter(
        "node_custody_liveness_transition_suspect_total",
        service,
        s -> s.snapshot().transitionsSuspectTotal(),
        "Custody-liveness transitions into SUSPECT (cumulative)");
    counter(
        "node_custody_liveness_transition_unresponsive_total",
        service,
        s -> s.snapshot().transitionsUnresponsiveTotal(),
        "Custody-liveness transitions into UNRESPONSIVE (cumulative)");
    counter(
        "node_custody_liveness_transition_escalated_total",
        service,
        s -> s.snapshot().transitionsEscalatedTotal(),
        "Custody-liveness transitions into ESCALATED (cumulative)");
    counter(
        "node_custody_liveness_escalation_deferred_total",
        service,
        s -> s.snapshot().escalationDeferredTotal(),
        "Custody-liveness escalations deferred because tutor was unreachable;"
            + " custody TTL renewed instead of dropping the fragment (cumulative)");
    counter(
        "node_custody_liveness_expiry_escalation_total",
        service,
        s -> s.snapshot().expiryEscalationTotal(),
        "Custody fragments processed by expiry-escalation worker: TTL caducó sin"
            + " probe activo (cluster down / origen unreachable) y se disparó RETURN_TO_TUTOR"
            + " — cumulative count of fragments dispatched, not cycles");
  }

  private void registerRecoveryMeters() {
    final RecoveryObservabilityService service =
        recoveryProvider == null ? null : recoveryProvider.getIfAvailable();
    if (service == null) {
      return;
    }
    counter(
        "node_recovery_consistency_compensation_total",
        service,
        s -> s.snapshot().recoveryConsistencyCompensationTotal(),
        "Recovery store-flow compensations applied (cumulative)");
    counter(
        "node_recovery_consistency_reconciliation_total",
        service,
        s -> s.snapshot().recoveryConsistencyReconciliationTotal(),
        "Recovery metadata entries reconciled against missing payload (cumulative)");
    counter(
        "node_recovery_cleanup_run_total",
        service,
        s -> s.snapshot().recoveryCleanupRunTotal(),
        "Recovery maintenance cycles executed (cumulative)");
    counter(
        "node_recovery_cleanup_run_error_total",
        service,
        s -> s.snapshot().recoveryCleanupRunErrorTotal(),
        "Recovery maintenance cycles that completed with at least one error (cumulative)");

    // file integrity risk orchestrator.
    Gauge.builder(
            "node_recovery_file_integrity_risk_score_max",
            service,
            s -> s.snapshot().fileIntegrityRiskScoreMax())
        .description(
            "Max riskScore observed across files in the last FileIntegrityRiskOrchestrator cycle"
                + " (0..1 fraction; >= recompose-threshold-fraction triggers recompose)")
        .register(registry);
    Gauge.builder(
            "node_recovery_file_integrity_files_evaluated",
            service,
            s -> (double) s.snapshot().fileIntegrityFilesEvaluated())
        .description(
            "Files evaluated in the last FileIntegrityRiskOrchestrator cycle (point-in-time)")
        .register(registry);
    counter(
        "node_recovery_file_integrity_recompose_total",
        service,
        s -> s.snapshot().fileIntegrityRecomposeTotal(),
        "Files for which a total recompose (re-upload) was executed (cumulative)");
    counter(
        "node_recovery_file_integrity_recompose_failure_total",
        service,
        s -> s.snapshot().fileIntegrityRecomposeFailureTotal(),
        "Files for which recompose was attempted but failed (cumulative)");
    counter(
        "node_recovery_file_integrity_unrecoverable_total",
        service,
        s -> s.snapshot().fileIntegrityUnrecoverableTotal(),
        "Files marked FILE_UNRECOVERABLE (insufficient healthy placements) (cumulative)");
  }

  private <T> void counter(
      final String name,
      final T source,
      final java.util.function.ToLongFunction<T> extractor,
      final String description) {
    FunctionCounter.builder(name, source, s -> (double) extractor.applyAsLong(s))
        .description(description)
        .register(registry);
  }
}
