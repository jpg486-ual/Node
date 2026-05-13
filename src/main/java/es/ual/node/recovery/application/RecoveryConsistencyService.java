package es.ual.node.recovery.application;

import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPayloadPort;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPort;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maintains metadata/payload consistency for recovery custody. Su única responsabilidad es la
 * reconciliación metadata-without-payload, que detecta y elimina filas huérfanas tras
 * compensaciones fallidas.
 */
public final class RecoveryConsistencyService {

  private static final Logger LOGGER = LoggerFactory.getLogger(RecoveryConsistencyService.class);

  private final RecoveryOrphanFragmentPort recoveryOrphanFragmentPort;
  private final RecoveryOrphanFragmentPayloadPort recoveryOrphanFragmentPayloadPort;
  private final RecoveryProperties recoveryProperties;
  private final RecoveryObservabilityService recoveryObservabilityService;

  /** Creates service with default no-op observability tracker. */
  public RecoveryConsistencyService(
      final RecoveryOrphanFragmentPort recoveryOrphanFragmentPort,
      final RecoveryOrphanFragmentPayloadPort recoveryOrphanFragmentPayloadPort,
      final RecoveryProperties recoveryProperties) {
    this(
        recoveryOrphanFragmentPort,
        recoveryOrphanFragmentPayloadPort,
        recoveryProperties,
        RecoveryObservabilityService.noop());
  }

  /** Creates service. */
  public RecoveryConsistencyService(
      final RecoveryOrphanFragmentPort recoveryOrphanFragmentPort,
      final RecoveryOrphanFragmentPayloadPort recoveryOrphanFragmentPayloadPort,
      final RecoveryProperties recoveryProperties,
      final RecoveryObservabilityService recoveryObservabilityService) {
    if (recoveryOrphanFragmentPort == null
        || recoveryOrphanFragmentPayloadPort == null
        || recoveryProperties == null
        || recoveryObservabilityService == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.recoveryOrphanFragmentPort = recoveryOrphanFragmentPort;
    this.recoveryOrphanFragmentPayloadPort = recoveryOrphanFragmentPayloadPort;
    this.recoveryProperties = recoveryProperties;
    this.recoveryObservabilityService = recoveryObservabilityService;
  }

  /**
   * Runs one maintenance cycle.
   *
   * @return cycle outcome
   */
  public RecoveryConsistencyOutcome processMaintenance() {
    if (!recoveryProperties.getConsistency().isEnabled()) {
      return new RecoveryConsistencyOutcome(0);
    }

    recoveryObservabilityService.onCleanupRun();

    try {
      final StepResult reconcileResult = reconcileMissingPayload();
      final int reconciledMissingPayload = reconcileResult.affected();
      recoveryObservabilityService.onReconciledMissingPayload(reconciledMissingPayload);

      if (reconcileResult.errors() > 0) {
        recoveryObservabilityService.onCleanupRunError();
      }

      if (reconciledMissingPayload > 0 || reconcileResult.errors() > 0) {
        LOGGER
            .atInfo()
            .setMessage("Recovery consistency maintenance cycle")
            .addKeyValue("reconciledMissingPayload", reconciledMissingPayload)
            .addKeyValue("reconciliationErrors", reconcileResult.errors())
            .log();
      }

      return new RecoveryConsistencyOutcome(reconciledMissingPayload);
    } catch (RuntimeException exception) {
      recoveryObservabilityService.onCleanupRunError();
      throw exception;
    }
  }

  private StepResult reconcileMissingPayload() {
    final int batchSize =
        Math.max(1, recoveryProperties.getConsistency().getReconciliationBatchSize());
    final List<String> fragmentIds = recoveryOrphanFragmentPort.findAllFragmentIds(batchSize);

    int reconciled = 0;
    int errors = 0;
    for (String fragmentId : fragmentIds) {
      try {
        if (recoveryOrphanFragmentPayloadPort.exists(fragmentId)) {
          continue;
        }

        recoveryOrphanFragmentPort.deleteByFragmentId(fragmentId);
        reconciled++;

        LOGGER
            .atWarn()
            .setMessage("Recovery reconciliation removed metadata without payload")
            .addKeyValue("event", "RECONCILED_METADATA_WITHOUT_PAYLOAD")
            .addKeyValue("result", "success")
            .addKeyValue("fragmentId", fragmentId)
            .log();
      } catch (RuntimeException exception) {
        errors++;
        LOGGER
            .atWarn()
            .setMessage("Recovery reconciliation failed")
            .addKeyValue("event", "RECONCILED_METADATA_WITHOUT_PAYLOAD")
            .addKeyValue("result", "error")
            .addKeyValue("fragmentId", fragmentId)
            .addKeyValue("error", exception.getMessage())
            .log();
      }
    }
    return new StepResult(reconciled, errors);
  }

  private record StepResult(int affected, int errors) {}

  /** Immutable maintenance cycle counters. */
  public record RecoveryConsistencyOutcome(int reconciledMissingPayload) {}
}
