package es.ual.node.custodyliveness.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Scheduler periódico origen-side que ejecuta {@link OriginInverseProbeService#runOnce()}.
 *
 * <p>Intervalo: {@code node.custody-liveness.inverse-probe-check-interval-seconds} (default 30s).
 * Errores se loguean pero no propagan; el scheduler sigue activo en el siguiente tick.
 */
public class OriginInverseProbeWorker {

  private static final Logger LOGGER = LoggerFactory.getLogger(OriginInverseProbeWorker.class);

  private final OriginInverseProbeService service;

  /** Creates worker. */
  public OriginInverseProbeWorker(final OriginInverseProbeService service) {
    if (service == null) {
      throw new IllegalArgumentException("service must not be null");
    }
    this.service = service;
  }

  /** Tick periódico. */
  @Scheduled(
      fixedDelayString = "${node.custody-liveness.inverse-probe-check-interval-seconds:30}000")
  public void tick() {
    try {
      service.runOnce();
    } catch (RuntimeException exception) {
      LOGGER
          .atWarn()
          .setMessage("Origin inverse probe cycle failed (will retry next tick)")
          .addKeyValue("event", "CUSTODY_LIVENESS_INVERSE_CYCLE_FAILED")
          .addKeyValue("error", exception.getMessage())
          .log();
    }
  }
}
