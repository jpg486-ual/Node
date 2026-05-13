package es.ual.node.recovery.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Scheduler periódico origen-side que ejecuta {@link FileIntegrityRiskOrchestrator#runOnce()}.
 *
 * <p>Intervalo: {@code node.recovery.integrity-check-interval-seconds} (default 60s). Errores se
 * loguean pero no propagan; el scheduler sigue activo en el siguiente tick.
 */
public class FileIntegrityRiskWorker {

  private static final Logger LOGGER = LoggerFactory.getLogger(FileIntegrityRiskWorker.class);

  private final FileIntegrityRiskOrchestrator orchestrator;

  /** Creates worker. */
  public FileIntegrityRiskWorker(final FileIntegrityRiskOrchestrator orchestrator) {
    if (orchestrator == null) {
      throw new IllegalArgumentException("orchestrator must not be null");
    }
    this.orchestrator = orchestrator;
  }

  /** Tick periódico. */
  @Scheduled(fixedDelayString = "${node.recovery.integrity-check-interval-seconds:60}000")
  public void tick() {
    try {
      orchestrator.runOnce();
    } catch (RuntimeException exception) {
      LOGGER
          .atWarn()
          .setMessage("File integrity cycle failed (will retry next tick)")
          .addKeyValue("event", "FILE_INTEGRITY_CYCLE_FAILED")
          .addKeyValue("error", exception.getMessage())
          .log();
    }
  }
}
