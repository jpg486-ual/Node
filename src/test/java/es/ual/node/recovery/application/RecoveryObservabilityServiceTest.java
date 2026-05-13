package es.ual.node.recovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link RecoveryObservabilityService}. */
class RecoveryObservabilityServiceTest {

  @Test
  void snapshotReflectsRecordedCounters() {
    final RecoveryObservabilityService service = new RecoveryObservabilityService();

    service.onCleanupRun();
    service.onReconciledMissingPayload(1);
    service.onStoreCompensated();
    service.onCleanupRunError();

    final RecoveryMetricsSnapshot snapshot = service.snapshot();

    assertThat(snapshot.recoveryCleanupRunTotal()).isEqualTo(1);
    assertThat(snapshot.recoveryConsistencyReconciliationTotal()).isEqualTo(1);
    assertThat(snapshot.recoveryConsistencyCompensationTotal()).isEqualTo(1);
    assertThat(snapshot.recoveryCleanupRunErrorTotal()).isEqualTo(1);
    assertThat(snapshot.asNamedMetrics())
        .containsKeys(
            "recovery.consistency.compensation.total",
            "recovery.consistency.reconciliation.total",
            "recovery.cleanup.run.total",
            "recovery.cleanup.run.error.total");
  }

  @Test
  void noopTrackerKeepsCountersAtZero() {
    final RecoveryObservabilityService service = RecoveryObservabilityService.noop();

    service.onCleanupRun();
    service.onReconciledMissingPayload(3);
    service.onStoreCompensated();
    service.onCleanupRunError();

    final RecoveryMetricsSnapshot snapshot = service.snapshot();

    assertThat(snapshot.recoveryCleanupRunTotal()).isZero();
    assertThat(snapshot.recoveryConsistencyReconciliationTotal()).isZero();
    assertThat(snapshot.recoveryConsistencyCompensationTotal()).isZero();
    assertThat(snapshot.recoveryCleanupRunErrorTotal()).isZero();
  }
}
