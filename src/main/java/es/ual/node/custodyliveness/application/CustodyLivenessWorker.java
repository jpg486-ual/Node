package es.ual.node.custodyliveness.application;

import org.springframework.scheduling.annotation.Scheduled;

/** Scheduled worker for custody liveness processing. */
public class CustodyLivenessWorker {

  private final CustodyLivenessService service;

  /** Creates worker. */
  public CustodyLivenessWorker(final CustodyLivenessService service) {
    if (service == null) {
      throw new IllegalArgumentException("service must not be null");
    }
    this.service = service;
  }

  /** Processes due outbound probe sessions. */
  @Scheduled(fixedDelayString = "${node.custody-liveness.worker-fixed-delay-millis:5000}")
  public void processDue() {
    service.processDueOutboundSessions();
  }
}
