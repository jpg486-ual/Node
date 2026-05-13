package es.ual.node.discovery.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodically re-invokes {@link SelfDiscoveryRegistrar#registerSelf()} so the local node remains
 * an active candidate in every configured supernode's directory.
 *
 * <p>The registration was a one-shot at {@code ApplicationReadyEvent}. With the supernode-side
 * cleanup worker (see {@link DiscoveryCandidateCleanupWorker}) and the freshness filter on {@code
 * findActiveCandidates} (default {@code freshness-seconds=300}), an origin that does not renew
 * within that window stops being visible to its peers, which is exactly the desired aging
 * behaviour. This worker pays the renewal cost so a healthy origin stays visible indefinitely.
 *
 * <p>Best-effort semantics inherited from the registrar: per-supernode failures are logged but
 * never propagated; this scheduled method also catches and logs anything thrown so a transient
 * upstream failure does not poison the {@code @Scheduled} queue.
 */
public class SelfDiscoveryRenewalWorker {

  private static final Logger LOGGER = LoggerFactory.getLogger(SelfDiscoveryRenewalWorker.class);

  private final SelfDiscoveryRegistrar registrar;

  /**
   * Creates worker.
   *
   * @param registrar registrar to invoke on each tick
   */
  public SelfDiscoveryRenewalWorker(final SelfDiscoveryRegistrar registrar) {
    if (registrar == null) {
      throw new IllegalArgumentException("registrar must not be null");
    }
    this.registrar = registrar;
  }

  /**
   * Renews the registration on every configured supernode. Defaults aligned with {@code
   * application.properties} (60s renewal cadence + 30s initial delay), el property gana en runtime,
   * pero los defaults aquí coinciden para que un test sin override no diverja.
   */
  @Scheduled(
      fixedDelayString = "${node.discovery.renewal.interval-millis:60000}",
      initialDelayString = "${node.discovery.renewal.initial-delay-millis:30000}")
  public void renew() {
    try {
      final int succeeded = registrar.registerSelf();
      LOGGER
          .atInfo()
          .setMessage("Self-discovery renewal completed")
          .addKeyValue("supernodesSucceeded", succeeded)
          .log();
    } catch (RuntimeException exception) {
      LOGGER
          .atWarn()
          .setMessage("Self-discovery renewal failed completely")
          .addKeyValue("error", exception.getMessage())
          .log();
    }
  }
}
