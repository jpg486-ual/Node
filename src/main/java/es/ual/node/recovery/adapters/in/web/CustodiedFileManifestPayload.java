package es.ual.node.recovery.adapters.in.web;

import es.ual.node.recovery.domain.CustodiedFileManifest;
import java.time.Instant;
import java.util.List;

/** HTTP response payload representing a custodied FileManifest */
public record CustodiedFileManifestPayload(
    String fileId,
    String requesterNodeId,
    String directoryPath,
    String originalFileName,
    String originalFileHash,
    long originalSizeBytes,
    Long compressedSizeBytes,
    String compressionAlgorithm,
    int fragmentCount,
    long fragmentSize,
    int redundancyN,
    int redundancyK,
    List<String> fragmentHashes,
    String clientPlacementsJson,
    Instant storedAt,
    Instant lastSupervisedCheckAt,
    int consecutiveOriginFailures) {

  /**
   * Converts custody record to response payload. Drops {@code requesterPublicKey} from outbound
   * response since it is sensitive material already known by the requester.
   */
  public static CustodiedFileManifestPayload fromDomain(final CustodiedFileManifest manifest) {
    return new CustodiedFileManifestPayload(
        manifest.fileId(),
        manifest.requesterNodeId(),
        manifest.directoryPath(),
        manifest.originalFileName(),
        manifest.originalFileHash(),
        manifest.originalSizeBytes(),
        manifest.compressedSizeBytes(),
        manifest.compressionAlgorithm(),
        manifest.fragmentCount(),
        manifest.fragmentSize(),
        manifest.redundancyN(),
        manifest.redundancyK(),
        manifest.fragmentHashes(),
        manifest.clientPlacementsJson(),
        manifest.storedAt(),
        manifest.lastSupervisedCheckAt(),
        manifest.consecutiveOriginFailures());
  }
}
