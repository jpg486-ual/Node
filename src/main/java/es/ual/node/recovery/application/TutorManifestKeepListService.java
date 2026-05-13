package es.ual.node.recovery.application;

import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.recovery.domain.CustodiedFileManifest;
import es.ual.node.recovery.ports.out.CustodiedFileManifestPort;
import es.ual.node.recovery.ports.out.RemoteOriginKeepListClientPort;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tutor-side service que ejecuta el ciclo de keep-list:
 *
 * <ol>
 *   <li>Para cada {@code requesterNodeId} con manifests bajo custodia, resuelve su {@code baseUrl}
 *       via {@link NodeTopologyProperties#getSupervisedBaseUrls()}.
 *   <li>Pide al origen su whitelist actual via {@code GET /ops/tutor/manifest-keep-list}.
 *   <li>Si responde OK: purga los manifests no mencionados + marca {@code lastSupervisedCheckAt} y
 *       resetea {@code consecutiveOriginFailures} en los restantes.
 *   <li>Si NO responde: incrementa {@code consecutiveOriginFailures} pero NUNCA purga (protección
 *       fail-safe, origen presunto-caído).
 * </ol>
 */
public class TutorManifestKeepListService {

  private static final Logger LOGGER = LoggerFactory.getLogger(TutorManifestKeepListService.class);

  private final CustodiedFileManifestPort manifestPort;
  private final RemoteOriginKeepListClientPort keepListClient;
  private final NodeTopologyProperties topologyProperties;
  private final Clock clock;

  /** Creates service. */
  public TutorManifestKeepListService(
      final CustodiedFileManifestPort manifestPort,
      final RemoteOriginKeepListClientPort keepListClient,
      final NodeTopologyProperties topologyProperties,
      final Clock clock) {
    if (manifestPort == null
        || keepListClient == null
        || topologyProperties == null
        || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.manifestPort = manifestPort;
    this.keepListClient = keepListClient;
    this.topologyProperties = topologyProperties;
    this.clock = clock;
  }

  /** Runs one keep-list cycle. Returns counters. */
  public CycleSummary runOnce() {
    final Map<String, String> supervisedBaseUrls = topologyProperties.getSupervisedBaseUrls();
    final List<String> supervisedNodeIds = manifestPort.listSupervisedNodeIds();
    int probedNodes = 0;
    int purged = 0;
    int kept = 0;
    int silentNodes = 0;

    for (String supervisedNodeId : supervisedNodeIds) {
      final String baseUrl = supervisedBaseUrls.get(supervisedNodeId);
      if (baseUrl == null || baseUrl.isBlank()) {
        LOGGER
            .atWarn()
            .setMessage("Tutor keep-list: supervised baseUrl not configured — skipping")
            .addKeyValue("event", "TUTOR_KEEPLIST_BASEURL_MISSING")
            .addKeyValue("supervisedNodeId", supervisedNodeId)
            .log();
        continue;
      }
      probedNodes++;
      final Instant now = Instant.now(clock);
      final List<String> keepList;
      try {
        keepList = keepListClient.fetchKeepList(baseUrl);
      } catch (RuntimeException ex) {
        silentNodes++;
        manifestPort.markSupervisedCheckFailed(supervisedNodeId, now);
        LOGGER
            .atWarn()
            .setMessage("Tutor keep-list: origin did NOT respond — manifest preservation guard")
            .addKeyValue("event", "ORIGIN_PRESUMED_DOWN")
            .addKeyValue("severity", "critical")
            .addKeyValue("supervisedNodeId", supervisedNodeId)
            .addKeyValue("error", ex.getMessage())
            .log();
        continue;
      }

      // Origen respondió OK → keepList trae fileIds que origen quiere conservar.
      final Set<String> keepSet = new HashSet<>(keepList);
      final List<CustodiedFileManifest> mineForSupervised =
          manifestPort.findByRequesterNodeId(supervisedNodeId);
      final List<String> toPurge =
          mineForSupervised.stream()
              .map(CustodiedFileManifest::fileId)
              .filter(fid -> !keepSet.contains(fid))
              .toList();
      if (!toPurge.isEmpty()) {
        purged += manifestPort.deleteByFileIds(toPurge);
      }
      kept += mineForSupervised.size() - toPurge.size();
      manifestPort.markSupervisedCheckOk(supervisedNodeId, now);

      LOGGER
          .atInfo()
          .setMessage("Tutor keep-list cycle for supervised node")
          .addKeyValue("event", "TUTOR_KEEPLIST_CYCLE")
          .addKeyValue("supervisedNodeId", supervisedNodeId)
          .addKeyValue("kept", mineForSupervised.size() - toPurge.size())
          .addKeyValue("purged", toPurge.size())
          .log();
    }

    return new CycleSummary(probedNodes, purged, kept, silentNodes);
  }

  /** Cycle counters. */
  public record CycleSummary(
      int probedNodes, int purgedManifests, int keptManifests, int silentNodes) {}
}
