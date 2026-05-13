package es.ual.node.recovery.application;

import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.filesystem.ports.out.FileManifestPort;
import es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort;
import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.recovery.ports.out.RemoteTutorManifestInventoryClientPort;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Origen-side service que detecta silencio del tutor (no recibe keep-list) y dispara endpoint
 * inverso ({@code GET /recovery/file-manifests/inventory}) preguntando al tutor qué manifests
 * custodia para él. Re-emite los manifests faltantes via {@code POST /recovery/file-manifests}
 * (replicación normal).
 */
public class OriginTutorManifestSyncService {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(OriginTutorManifestSyncService.class);

  private final FileManifestPort localManifestPort;
  private final RemoteTutorManifestInventoryClientPort inventoryClient;
  private final RemoteFileManifestStorePort manifestStorePort;
  private final NodeTopologyProperties topologyProperties;
  private final TutorManifestStorePortAdapter storeAdapter;

  /**
   * Adapter functional that knows how to build the placements list for a given fileId. Provided por
   * the wiring porque la lookup de placements vive en otro módulo (filesystem/persistence).
   */
  @FunctionalInterface
  public interface TutorManifestStorePortAdapter {
    /**
     * Resolves the {@code List<FragmentPlacement>} associated with a file id locally. Returns null
     * if no placements are available (the manifest cannot be re-emitted).
     */
    java.util.List<es.ual.node.filesystem.domain.FragmentPlacement> resolvePlacements(
        String fileId);
  }

  /** Creates service. */
  public OriginTutorManifestSyncService(
      final FileManifestPort localManifestPort,
      final RemoteTutorManifestInventoryClientPort inventoryClient,
      final RemoteFileManifestStorePort manifestStorePort,
      final NodeTopologyProperties topologyProperties,
      final TutorManifestStorePortAdapter storeAdapter) {
    if (localManifestPort == null
        || inventoryClient == null
        || manifestStorePort == null
        || topologyProperties == null
        || storeAdapter == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.localManifestPort = localManifestPort;
    this.inventoryClient = inventoryClient;
    this.manifestStorePort = manifestStorePort;
    this.topologyProperties = topologyProperties;
    this.storeAdapter = storeAdapter;
  }

  /** Runs one sync cycle. */
  public CycleSummary runOnce() {
    final String tutorBaseUrl = topologyProperties.getTutorBaseUrl();
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      return new CycleSummary(0, 0, false);
    }
    final List<String> tutorInventory;
    try {
      tutorInventory = inventoryClient.fetchInventory(tutorBaseUrl);
    } catch (RuntimeException ex) {
      LOGGER
          .atWarn()
          .setMessage("Tutor inventory probe failed — tutor presumed down")
          .addKeyValue("event", "TUTOR_PRESUMED_DOWN")
          .addKeyValue("severity", "critical")
          .addKeyValue("tutorBaseUrl", tutorBaseUrl)
          .addKeyValue("error", ex.getMessage())
          .log();
      return new CycleSummary(0, 0, true);
    }

    final Set<String> tutorSet = new HashSet<>(tutorInventory);
    final List<String> localFileIds = localManifestPort.findAllFileIds();
    int reEmitted = 0;
    int errors = 0;

    for (String fileId : localFileIds) {
      if (tutorSet.contains(fileId)) {
        continue;
      }
      final FileManifest manifest = localManifestPort.findByFileId(fileId).orElse(null);
      if (manifest == null) {
        continue;
      }
      final List<es.ual.node.filesystem.domain.FragmentPlacement> placements =
          storeAdapter.resolvePlacements(fileId);
      if (placements == null) {
        continue;
      }
      try {
        manifestStorePort.store(manifest, placements, tutorBaseUrl);
        reEmitted++;
        LOGGER
            .atWarn()
            .setMessage("Manifest re-emitted to tutor after silence detection")
            .addKeyValue("event", "TUTOR_MANIFEST_RESEND")
            .addKeyValue("severity", "medium")
            .addKeyValue("fileId", fileId)
            .log();
      } catch (RuntimeException ex) {
        errors++;
        LOGGER
            .atWarn()
            .setMessage("Manifest re-emission failed during inverse sync")
            .addKeyValue("event", "TUTOR_MANIFEST_RESEND_FAILED")
            .addKeyValue("fileId", fileId)
            .addKeyValue("error", ex.getMessage())
            .log();
      }
    }
    return new CycleSummary(reEmitted, errors, false);
  }

  /** Cycle counters. */
  public record CycleSummary(int reEmittedManifests, int errors, boolean tutorSilent) {}
}
