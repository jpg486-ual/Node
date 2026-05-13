package es.ual.node.recovery.application;

import org.springframework.scheduling.annotation.Scheduled;

/** Scheduled worker for recovery consistency maintenance. */
public final class RecoveryConsistencyWorker {

  private final RecoveryConsistencyService recoveryConsistencyService;

  /** Creates worker. */
  public RecoveryConsistencyWorker(final RecoveryConsistencyService recoveryConsistencyService) {
    if (recoveryConsistencyService == null) {
      throw new IllegalArgumentException("recoveryConsistencyService must not be null");
    }
    this.recoveryConsistencyService = recoveryConsistencyService;
  }

  /** Processes one consistency maintenance cycle. */
  @Scheduled(fixedDelayString = "${node.recovery.consistency.worker-fixed-delay-millis:30000}")
  public void processDue() {
    recoveryConsistencyService.processMaintenance();
  }
}
