package es.ual.node.recovery.application;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks recovery consistency and compensation counters + file integrity orchestrator state.
 *
 * <p>Seguimiento del {@link FileIntegrityRiskOrchestrator} `riskScoreMax` (último ciclo) +
 * `filesEvaluated` y counters acumulados de recompose y unrecoverable.
 */
public class RecoveryObservabilityService {

  private final boolean enabled;
  private final AtomicLong consistencyCompensationTotal;
  private final AtomicLong consistencyReconciliationTotal;
  private final AtomicLong cleanupRunTotal;
  private final AtomicLong cleanupRunErrorTotal;
  private final AtomicLong fileIntegrityRecomposeTotal;
  private final AtomicLong fileIntegrityRecomposeFailureTotal;
  private final AtomicLong fileIntegrityUnrecoverableTotal;

  // Overwritten en cada ciclo del FileIntegrityRiskOrchestrator. Long.doubleToRaw
  // serializa el double atómicamente sobre AtomicLong (evita locks).
  private final AtomicLong fileIntegrityRiskScoreMaxBits;
  private final AtomicLong fileIntegrityFilesEvaluatedLast;

  /** Creates enabled observability tracker. */
  public RecoveryObservabilityService() {
    this(true);
  }

  private RecoveryObservabilityService(final boolean enabled) {
    this.enabled = enabled;
    this.consistencyCompensationTotal = new AtomicLong();
    this.consistencyReconciliationTotal = new AtomicLong();
    this.cleanupRunTotal = new AtomicLong();
    this.cleanupRunErrorTotal = new AtomicLong();
    this.fileIntegrityRecomposeTotal = new AtomicLong();
    this.fileIntegrityRecomposeFailureTotal = new AtomicLong();
    this.fileIntegrityUnrecoverableTotal = new AtomicLong();
    this.fileIntegrityRiskScoreMaxBits = new AtomicLong(Double.doubleToRawLongBits(0.0d));
    this.fileIntegrityFilesEvaluatedLast = new AtomicLong();
  }

  /** Creates no-op tracker used in tests without metrics wiring. */
  public static RecoveryObservabilityService noop() {
    return new RecoveryObservabilityService(false);
  }

  /** Records a successful compensation attempt in store flow. */
  public void onStoreCompensated() {
    if (!enabled) {
      return;
    }
    consistencyCompensationTotal.incrementAndGet();
  }

  /** Records amount of reconciled metadata entries. */
  public void onReconciledMissingPayload(final long count) {
    if (!enabled || count <= 0L) {
      return;
    }
    consistencyReconciliationTotal.addAndGet(count);
  }

  /** Records one maintenance cycle execution. */
  public void onCleanupRun() {
    if (!enabled) {
      return;
    }
    cleanupRunTotal.incrementAndGet();
  }

  /** Records one maintenance cycle with at least one error. */
  public void onCleanupRunError() {
    if (!enabled) {
      return;
    }
    cleanupRunErrorTotal.incrementAndGet();
  }

  /**
   * Records a completed cycle of {@link FileIntegrityRiskOrchestrator}. Overwrites the gauges
   * (riskScoreMax + filesEvaluated) with the values from this cycle and accumulates the action
   * counters.
   *
   * @param riskScoreMax max risk score observed across files in this cycle (0 if no file evaluated)
   * @param filesEvaluated number of files evaluated in this cycle
   * @param recomposed number of files recomposed in this cycle
   * @param recomposeFailures number of files where recompose was attempted but failed
   * @param unrecoverable number of files marked FILE_UNRECOVERABLE in this cycle
   */
  public void onFileIntegrityCycle(
      final double riskScoreMax,
      final long filesEvaluated,
      final long recomposed,
      final long recomposeFailures,
      final long unrecoverable) {
    if (!enabled) {
      return;
    }
    final double clamped = Double.isFinite(riskScoreMax) ? Math.max(0.0d, riskScoreMax) : 0.0d;
    fileIntegrityRiskScoreMaxBits.set(Double.doubleToRawLongBits(clamped));
    fileIntegrityFilesEvaluatedLast.set(Math.max(0L, filesEvaluated));
    if (recomposed > 0L) {
      fileIntegrityRecomposeTotal.addAndGet(recomposed);
    }
    if (recomposeFailures > 0L) {
      fileIntegrityRecomposeFailureTotal.addAndGet(recomposeFailures);
    }
    if (unrecoverable > 0L) {
      fileIntegrityUnrecoverableTotal.addAndGet(unrecoverable);
    }
  }

  /** Returns a point-in-time metrics snapshot. */
  public RecoveryMetricsSnapshot snapshot() {
    return new RecoveryMetricsSnapshot(
        consistencyCompensationTotal.get(),
        consistencyReconciliationTotal.get(),
        cleanupRunTotal.get(),
        cleanupRunErrorTotal.get(),
        Double.longBitsToDouble(fileIntegrityRiskScoreMaxBits.get()),
        fileIntegrityFilesEvaluatedLast.get(),
        fileIntegrityRecomposeTotal.get(),
        fileIntegrityRecomposeFailureTotal.get(),
        fileIntegrityUnrecoverableTotal.get());
  }
}
