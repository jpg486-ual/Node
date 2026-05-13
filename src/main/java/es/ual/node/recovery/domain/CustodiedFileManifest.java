package es.ual.node.recovery.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable metadata of a {@link es.ual.node.negotiation.domain.FileManifest} held under proactive
 * tutor custody.
 *
 * <p>El {@code clientPlacementsJson} field sigue siendo opaque JSON de la lista de placements; el
 * tutor lo persiste verbatim y nunca lo interpreta.
 */
public record CustodiedFileManifest(
    String fileId,
    String requesterNodeId,
    String requesterPublicKey,
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
    String clientBlocksJson,
    Instant storedAt,
    Instant lastSupervisedCheckAt,
    int consecutiveOriginFailures) {

  /** Creates validated manifest custody record. */
  public CustodiedFileManifest {
    Objects.requireNonNull(fileId, "fileId");
    Objects.requireNonNull(requesterNodeId, "requesterNodeId");
    Objects.requireNonNull(requesterPublicKey, "requesterPublicKey");
    Objects.requireNonNull(directoryPath, "directoryPath");
    Objects.requireNonNull(originalFileName, "originalFileName");
    Objects.requireNonNull(originalFileHash, "originalFileHash");
    Objects.requireNonNull(fragmentHashes, "fragmentHashes");
    Objects.requireNonNull(storedAt, "storedAt");
    if (fileId.isBlank()
        || requesterNodeId.isBlank()
        || requesterPublicKey.isBlank()
        || directoryPath.isBlank()
        || originalFileName.isBlank()
        || originalFileHash.isBlank()) {
      throw new IllegalArgumentException("string fields must not be blank");
    }
    if (originalSizeBytes <= 0 || fragmentCount <= 0 || fragmentSize <= 0) {
      throw new IllegalArgumentException("numeric fields must be positive");
    }
    if (redundancyN <= 0 || redundancyK <= 0 || redundancyN < redundancyK) {
      throw new IllegalArgumentException("invalid redundancy values");
    }
    if (consecutiveOriginFailures < 0) {
      throw new IllegalArgumentException("consecutiveOriginFailures must not be negative");
    }
    fragmentHashes = List.copyOf(fragmentHashes);
  }

  /**
   * Convenience constructor. Defaults {@code lastSupervisedCheckAt=null,
   * consecutiveOriginFailures=0}. Aplicado en el flow de upload normal.
   */
  public CustodiedFileManifest(
      final String fileId,
      final String requesterNodeId,
      final String requesterPublicKey,
      final String directoryPath,
      final String originalFileName,
      final String originalFileHash,
      final long originalSizeBytes,
      final Long compressedSizeBytes,
      final String compressionAlgorithm,
      final int fragmentCount,
      final long fragmentSize,
      final int redundancyN,
      final int redundancyK,
      final List<String> fragmentHashes,
      final String clientPlacementsJson,
      final String clientBlocksJson,
      final Instant storedAt) {
    this(
        fileId,
        requesterNodeId,
        requesterPublicKey,
        directoryPath,
        originalFileName,
        originalFileHash,
        originalSizeBytes,
        compressedSizeBytes,
        compressionAlgorithm,
        fragmentCount,
        fragmentSize,
        redundancyN,
        redundancyK,
        fragmentHashes,
        clientPlacementsJson,
        clientBlocksJson,
        storedAt,
        null,
        0);
  }

  /**
   * Devuelve copia con {@code lastSupervisedCheckAt} actualizado y {@code
   * consecutiveOriginFailures=0}, origen respondió OK al probe del tutor.
   */
  public CustodiedFileManifest withSupervisedCheckOk(final Instant at) {
    return new CustodiedFileManifest(
        fileId,
        requesterNodeId,
        requesterPublicKey,
        directoryPath,
        originalFileName,
        originalFileHash,
        originalSizeBytes,
        compressedSizeBytes,
        compressionAlgorithm,
        fragmentCount,
        fragmentSize,
        redundancyN,
        redundancyK,
        fragmentHashes,
        clientPlacementsJson,
        clientBlocksJson,
        storedAt,
        at,
        0);
  }

  /**
   * Devuelve copia con {@code consecutiveOriginFailures} incrementado, origen no respondió.
   * Manifest se mantiene; el tutor no purga.
   */
  public CustodiedFileManifest withSupervisedCheckFailed(final Instant at) {
    return new CustodiedFileManifest(
        fileId,
        requesterNodeId,
        requesterPublicKey,
        directoryPath,
        originalFileName,
        originalFileHash,
        originalSizeBytes,
        compressedSizeBytes,
        compressionAlgorithm,
        fragmentCount,
        fragmentSize,
        redundancyN,
        redundancyK,
        fragmentHashes,
        clientPlacementsJson,
        clientBlocksJson,
        storedAt,
        at,
        consecutiveOriginFailures + 1);
  }
}
