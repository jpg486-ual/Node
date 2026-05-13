package es.ual.node.recovery.application;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * Scheduled worker que dispara periódicamente el ciclo del {@link TutorManifestKeepListService}.
 */
public class TutorManifestKeepListWorker {

  private final TutorManifestKeepListService service;

  /** Creates worker. */
  public TutorManifestKeepListWorker(final TutorManifestKeepListService service) {
    if (service == null) {
      throw new IllegalArgumentException("service must not be null");
    }
    this.service = service;
  }

  /** Tick. Cadencia parametrizable via {@code node.recovery.manifest-keep-interval-seconds}. */
  @Scheduled(
      fixedDelayString = "${node.recovery.manifest-keep-interval-seconds:300}000",
      initialDelayString = "${node.recovery.manifest-keep-interval-seconds:300}000")
  public void tick() {
    service.runOnce();
  }
}
