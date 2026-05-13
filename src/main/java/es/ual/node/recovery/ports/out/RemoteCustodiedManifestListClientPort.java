package es.ual.node.recovery.ports.out;

import es.ual.node.recovery.domain.CustodiedFileManifest;
import java.util.List;

/**
 * Outbound port for fetching the list of custodied manifests from the configured tutor when the
 * node starts in restore mode. Implementations must perform inter-node signed HTTP {@code GET
 * /recovery/file-manifests} against {@code node.topology.tutorBaseUrl}.
 */
public interface RemoteCustodiedManifestListClientPort {

  /**
   * Returns the list of manifests the tutor holds for the local node identity.
   *
   * @return list of custodied manifests; empty when none
   */
  List<CustodiedFileManifest> fetchManifests();
}
