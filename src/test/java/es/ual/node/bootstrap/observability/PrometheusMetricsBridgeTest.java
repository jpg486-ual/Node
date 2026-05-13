package es.ual.node.bootstrap.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import es.ual.node.custodyliveness.application.CustodyLivenessObservabilityService;
import es.ual.node.custodyliveness.domain.CustodyProbeDirection;
import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import es.ual.node.custodyliveness.domain.CustodyProbeStatus;
import es.ual.node.discovery.application.DiscoveryMetricsSnapshot;
import es.ual.node.discovery.application.DiscoveryObservabilityService;
import es.ual.node.discovery.domain.DiscoveryRetryStatus;
import es.ual.node.discovery.ports.out.DiscoveryCandidateDirectoryPort;
import es.ual.node.discovery.ports.out.DiscoveryRetryQueuePort;
import es.ual.node.recovery.application.RecoveryMetricsSnapshot;
import es.ual.node.recovery.application.RecoveryObservabilityService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link PrometheusMetricsBridge}: cada meter registrado refleja el mismo valor que
 * el snapshot() JSON en el mismo instante (invariante dual-emit).
 */
@ExtendWith(MockitoExtension.class)
class PrometheusMetricsBridgeTest {

  @Mock private DiscoveryRetryQueuePort retryQueuePort;
  @Mock private DiscoveryCandidateDirectoryPort candidateDirectoryPort;

  private MeterRegistry registry;
  private DiscoveryObservabilityService discoveryService;
  private CustodyLivenessObservabilityService custodyLivenessService;
  private RecoveryObservabilityService recoveryService;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    discoveryService = new DiscoveryObservabilityService(retryQueuePort, candidateDirectoryPort);
    custodyLivenessService = new CustodyLivenessObservabilityService();
    recoveryService = new RecoveryObservabilityService();

    new PrometheusMetricsBridge(
            registry,
            providerOf(discoveryService),
            providerOf(custodyLivenessService),
            providerOf(recoveryService))
        .registerMeters();
  }

  @Test
  void discoveryGaugesReflectRetryQueueAndCandidateDirectoryCounts() {
    when(retryQueuePort.countByStatus(DiscoveryRetryStatus.PENDING)).thenReturn(7L);
    when(retryQueuePort.countByStatus(DiscoveryRetryStatus.RESOLVED)).thenReturn(12L);
    when(retryQueuePort.countByStatus(DiscoveryRetryStatus.FAILED)).thenReturn(3L);
    when(candidateDirectoryPort.countActiveCandidates()).thenReturn(5L);

    assertGaugeValue("node_discovery_queue_pending", 7d);
    assertGaugeValue("node_discovery_queue_resolved", 12d);
    assertGaugeValue("node_discovery_queue_failed", 3d);
    assertGaugeValue("node_discovery_candidates_active", 5d);

    final DiscoveryMetricsSnapshot snapshot = discoveryService.snapshot();
    assertEquals(snapshot.pendingQueueCount(), (long) gaugeValue("node_discovery_queue_pending"));
    assertEquals(snapshot.failedQueueCount(), (long) gaugeValue("node_discovery_queue_failed"));
  }

  @Test
  void custodyLivenessCountersReflectService() {
    custodyLivenessService.onInboundProbeHandled();
    custodyLivenessService.onInboundProbeHandled();
    custodyLivenessService.onOutboundSessionScheduled(false);
    custodyLivenessService.onOutboundProbeSuccess();
    custodyLivenessService.onTransition(
        probeSession(CustodyProbeStatus.ACTIVE), probeSession(CustodyProbeStatus.SUSPECT));

    assertEquals(2L, (long) counterValue("node_custody_liveness_inbound_total"));
    assertEquals(1L, (long) counterValue("node_custody_liveness_outbound_scheduled_total"));
    assertEquals(1L, (long) counterValue("node_custody_liveness_outbound_success_total"));
    assertEquals(1L, (long) counterValue("node_custody_liveness_transition_suspect_total"));
  }

  @Test
  void recoveryCountersReflectMaintenanceEvents() {
    recoveryService.onStoreCompensated();
    recoveryService.onReconciledMissingPayload(4L);
    recoveryService.onCleanupRun();
    recoveryService.onCleanupRun();
    recoveryService.onCleanupRunError();

    final RecoveryMetricsSnapshot snapshot = recoveryService.snapshot();
    assertEquals(
        snapshot.recoveryConsistencyCompensationTotal(),
        (long) counterValue("node_recovery_consistency_compensation_total"));
    assertEquals(
        snapshot.recoveryConsistencyReconciliationTotal(),
        (long) counterValue("node_recovery_consistency_reconciliation_total"));
    assertEquals(
        snapshot.recoveryCleanupRunTotal(), (long) counterValue("node_recovery_cleanup_run_total"));
    assertEquals(
        snapshot.recoveryCleanupRunErrorTotal(),
        (long) counterValue("node_recovery_cleanup_run_error_total"));
  }

  @Test
  void unavailableProviderDoesNotRegisterMeters() {
    final MeterRegistry emptyRegistry = new SimpleMeterRegistry();
    new PrometheusMetricsBridge(emptyRegistry, emptyProvider(), emptyProvider(), emptyProvider())
        .registerMeters();
    assertEquals(0, emptyRegistry.getMeters().size());
  }

  private void assertGaugeValue(final String name, final double expected) {
    assertNotNull(registry.find(name).gauge(), "gauge " + name + " debe estar registrado");
    assertEquals(expected, gaugeValue(name), 1e-9, "gauge " + name);
  }

  private double gaugeValue(final String name) {
    return registry.get(name).gauge().value();
  }

  private double counterValue(final String name) {
    return registry.get(name).functionCounter().count();
  }

  private static <T> ObjectProvider<T> providerOf(final T instance) {
    @SuppressWarnings("unchecked")
    final ObjectProvider<T> provider =
        (ObjectProvider<T>)
            org.mockito.Mockito.mock(ObjectProvider.class, org.mockito.Mockito.RETURNS_DEFAULTS);
    when(provider.getIfAvailable()).thenReturn(instance);
    return provider;
  }

  private static <T> ObjectProvider<T> emptyProvider() {
    @SuppressWarnings("unchecked")
    final ObjectProvider<T> provider =
        (ObjectProvider<T>)
            org.mockito.Mockito.mock(ObjectProvider.class, org.mockito.Mockito.RETURNS_DEFAULTS);
    when(provider.getIfAvailable()).thenReturn(null);
    return provider;
  }

  private static CustodyProbeSession probeSession(final CustodyProbeStatus status) {
    final Instant now = Instant.parse("2026-04-25T10:00:00Z");
    return CustodyProbeSession.withoutRemoteTutor(
        "session-" + status,
        "remote-node",
        CustodyProbeDirection.OUTBOUND,
        status,
        0,
        null,
        null,
        null,
        null,
        null,
        now,
        now);
  }
}
