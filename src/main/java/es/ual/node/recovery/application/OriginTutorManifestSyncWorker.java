package es.ual.node.recovery.application;

import org.springframework.scheduling.annotation.Scheduled;

/** Scheduled worker que dispara periódicamente {@link OriginTutorManifestSyncService}. */
public class OriginTutorManifestSyncWorker {

  private final OriginTutorManifestSyncService service;

  /** Creates worker. */
  public OriginTutorManifestSyncWorker(final OriginTutorManifestSyncService service) {
    if (service == null) {
      throw new IllegalArgumentException("service must not be null");
    }
    this.service = service;
  }

  /** Tick. Cadencia parametrizable via {@code node.recovery.tutor-sync-interval-seconds}. */
  @Scheduled(
      fixedDelayString = "${node.recovery.tutor-sync-interval-seconds:600}000",
      initialDelayString = "${node.recovery.tutor-sync-interval-seconds:600}000")
  public void tick() {
    service.runOnce();
  }
}
