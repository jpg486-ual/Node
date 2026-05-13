package es.ual.node.custodyliveness.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Scheduler periódico custodian-side que dispara el probe de keep-list al origen.
 *
 * <p>El intervalo es {@code node.custody-liveness.interval-seconds} (default 60s). Cada tick invoca
 * {@link CustodianOutboundKeepListService#runOnce()}. Errores se loguean pero no propagan; el
 * scheduler sigue activo en el siguiente tick.
 */
public class CustodianProbeWorker {

  private static final Logger LOGGER = LoggerFactory.getLogger(CustodianProbeWorker.class);

  private final CustodianOutboundKeepListService service;

  /** Creates worker. */
  public CustodianProbeWorker(final CustodianOutboundKeepListService service) {
    if (service == null) {
      throw new IllegalArgumentException("service must not be null");
    }
    this.service = service;
  }

  /** Tick periódico. */
  @Scheduled(fixedDelayString = "${node.custody-liveness.interval-seconds:60}000")
  public void tick() {
    try {
      final CustodianOutboundKeepListService.CycleSummary summary = service.runOnce();
      if (summary.probesSent() > 0 || summary.totalPurged() > 0 || summary.requesterErrors() > 0) {
        LOGGER
            .atInfo()
            .setMessage("Custodian outbound keep-list probe cycle complete")
            .addKeyValue("event", "CUSTODY_LIVENESS_OUTBOUND_CYCLE")
            .addKeyValue("probesSent", summary.probesSent())
            .addKeyValue("totalPurged", summary.totalPurged())
            .addKeyValue("requesterErrors", summary.requesterErrors())
            .log();
      }
    } catch (RuntimeException exception) {
      LOGGER
          .atWarn()
          .setMessage("Custodian outbound keep-list cycle failed (will retry next tick)")
          .addKeyValue("event", "CUSTODY_LIVENESS_OUTBOUND_CYCLE_FAILED")
          .addKeyValue("error", exception.getMessage())
          .log();
    }
  }
}
