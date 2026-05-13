package es.ual.node.recovery.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable snapshot of recovery consistency + file integrity metrics. */
public record RecoveryMetricsSnapshot(
    long recoveryConsistencyCompensationTotal,
    long recoveryConsistencyReconciliationTotal,
    long recoveryCleanupRunTotal,
    long recoveryCleanupRunErrorTotal,
    double fileIntegrityRiskScoreMax,
    long fileIntegrityFilesEvaluated,
    long fileIntegrityRecomposeTotal,
    long fileIntegrityRecomposeFailureTotal,
    long fileIntegrityUnrecoverableTotal) {

  /** Exposes metrics with stable names expected by ops documentation. */
  public Map<String, Number> asNamedMetrics() {
    final Map<String, Number> values = new LinkedHashMap<>();
    values.put("recovery.consistency.compensation.total", recoveryConsistencyCompensationTotal);
    values.put("recovery.consistency.reconciliation.total", recoveryConsistencyReconciliationTotal);
    values.put("recovery.cleanup.run.total", recoveryCleanupRunTotal);
    values.put("recovery.cleanup.run.error.total", recoveryCleanupRunErrorTotal);
    values.put("recovery.file.integrity.risk.score.max", fileIntegrityRiskScoreMax);
    values.put("recovery.file.integrity.files.evaluated", fileIntegrityFilesEvaluated);
    values.put("recovery.file.integrity.recompose.total", fileIntegrityRecomposeTotal);
    values.put(
        "recovery.file.integrity.recompose.failure.total", fileIntegrityRecomposeFailureTotal);
    values.put("recovery.file.integrity.unrecoverable.total", fileIntegrityUnrecoverableTotal);
    return Collections.unmodifiableMap(values);
  }
}
