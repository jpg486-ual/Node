package es.ual.node.discovery.application;

import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import es.ual.node.discovery.ports.out.RemoteDiscoveryCandidateClientPort;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the local node as a candidate in the discovery directories of all configured
 * supernodes. Renewed periodically by {@link SelfDiscoveryRenewalWorker} (default 60s — alineado
 * con la freshness window de 300s del supernodo, da margen de 5x antes de quedar invisible).
 *
 * <p>Best-effort: failures against individual supernodes are logged but do not abort the
 * registration loop, so a partially available cluster still gets the local node listed in the
 * supernodes that are reachable. Idempotent at the protocol level (the upsert endpoint accepts
 * repeated calls with the same payload), so running it once at startup is sufficient.
 */
public class SelfDiscoveryRegistrar {

  private static final Logger LOGGER = LoggerFactory.getLogger(SelfDiscoveryRegistrar.class);
  private static final long DEFAULT_REQUESTED_BUCKET = 1024L;
  private static final Set<Long> DEFAULT_ACCEPTED_BUCKETS = Set.of(1024L, 2048L, 4096L);

  private final NodeIdentityContext nodeIdentityContext;
  private final RemoteDiscoveryCandidateClientPort remoteClientPort;
  private final List<String> discoverySupernodes;
  private final String failureDomain;
  private final String localBaseUrl;

  /** Creates registrar. */
  public SelfDiscoveryRegistrar(
      final NodeIdentityContext nodeIdentityContext,
      final RemoteDiscoveryCandidateClientPort remoteClientPort,
      final List<String> discoverySupernodes,
      final String failureDomain,
      final String localBaseUrl) {
    if (nodeIdentityContext == null || remoteClientPort == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.nodeIdentityContext = nodeIdentityContext;
    this.remoteClientPort = remoteClientPort;
    this.discoverySupernodes =
        discoverySupernodes == null ? List.of() : List.copyOf(discoverySupernodes);
    this.failureDomain = failureDomain == null ? "" : failureDomain.trim();
    this.localBaseUrl = localBaseUrl == null ? "" : localBaseUrl.trim();
  }

  /**
   * Registers the local node in every configured supernode directory. Returns the number of
   * successful upserts so callers (and tests) can verify the outcome.
   *
   * @return count of supernodes where the upsert succeeded
   */
  public int registerSelf() {
    if (failureDomain.isEmpty()) {
      LOGGER
          .atWarn()
          .setMessage("Skipping self-discovery registration: node.failure-domain is not configured")
          .log();
      return 0;
    }
    if (localBaseUrl.isEmpty()) {
      LOGGER
          .atWarn()
          .setMessage(
              "Skipping self-discovery registration: node.discovery.local-base-url is not"
                  + " configured")
          .log();
      return 0;
    }
    if (discoverySupernodes.isEmpty()) {
      LOGGER
          .atWarn()
          .setMessage(
              "Skipping self-discovery registration: node.topology.discoverySupernodes is empty")
          .log();
      return 0;
    }

    final DiscoveryCandidateProfile profile =
        new DiscoveryCandidateProfile(
            nodeIdentityContext.nodeId(),
            failureDomain,
            localBaseUrl,
            DEFAULT_REQUESTED_BUCKET,
            DEFAULT_ACCEPTED_BUCKETS);

    int successCount = 0;
    for (String baseUrl : discoverySupernodes) {
      try {
        remoteClientPort.upsertCandidate(baseUrl, profile);
        successCount++;
      } catch (RuntimeException exception) {
        LOGGER
            .atWarn()
            .setMessage("Self-discovery registration failed for one supernode")
            .addKeyValue("baseUrl", baseUrl)
            .addKeyValue("nodeId", profile.nodeId())
            .addKeyValue("failureDomain", profile.failureDomain())
            .addKeyValue("error", exception.getMessage())
            .log();
      }
    }

    LOGGER
        .atInfo()
        .setMessage("Self-discovery registration completed")
        .addKeyValue("nodeId", profile.nodeId())
        .addKeyValue("failureDomain", profile.failureDomain())
        .addKeyValue("baseUrl", profile.baseUrl())
        .addKeyValue("supernodesAttempted", discoverySupernodes.size())
        .addKeyValue("supernodesSucceeded", successCount)
        .log();

    return successCount;
  }
}
