package es.ual.node.negotiation.domain;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable structured file metadata exchanged during negotiation. */
public final class FileManifest {

  private static final Pattern SHA256_HEX = Pattern.compile("^[a-fA-F0-9]{64}$");
  // Acepta cualquier carácter Unicode salvo los reservados para path traversal
  // (`/`, `\`, NULL byte) o en caracteres de control. Permite espacios,
  // acentos, ñ, paréntesis, etc. Los segmentos válidos en sistemas de
  // ficheros modernos.
  private static final Pattern DIRECTORY_PATH =
      Pattern.compile("^/(?:[^\\u0000-\\u001f/\\\\]+(?:/[^\\u0000-\\u001f/\\\\]+)*)?$");

  private final String fileId;
  private final String directoryPath;
  private final String originalFileName;
  private final long originalSizeBytes;
  private final Long compressedSizeBytes;
  private final String compressionAlgorithm;
  private final String originalFileHash;
  private final int fragmentCount;
  private final long fragmentSize;
  private final int redundancyN;
  private final int redundancyK;
  private final List<String> fragmentHashes;
  private final List<BlockManifest> blocks;

  /**
   * Creates validated manifest instance.
   *
   * @param fileId file uuid
   * @param directoryPath logical directory path of the file in the originating node FS (must start
   *     with {@code /}); the tutor treats it as opaque string. Username is intentionally not part
   *     of the manifest.
   * @param originalFileName original file name (no path separators)
   * @param originalSizeBytes original size in bytes
   * @param compressedSizeBytes compressed size in bytes when applicable
   * @param compressionAlgorithm compression algorithm when applicable
   * @param originalFileHash original file SHA-256 hash
   * @param fragmentCount fragment count
   * @param fragmentSize fragment size in bytes
   * @param redundancyN reed-solomon n value
   * @param redundancyK reed-solomon k value
   * @param fragmentHashes optional fragment hash list
   */
  public FileManifest(
      final String fileId,
      final String directoryPath,
      final String originalFileName,
      final long originalSizeBytes,
      final Long compressedSizeBytes,
      final String compressionAlgorithm,
      final String originalFileHash,
      final int fragmentCount,
      final long fragmentSize,
      final int redundancyN,
      final int redundancyK,
      final List<String> fragmentHashes) {
    this(
        fileId,
        directoryPath,
        originalFileName,
        originalSizeBytes,
        compressedSizeBytes,
        compressionAlgorithm,
        originalFileHash,
        fragmentCount,
        fragmentSize,
        redundancyN,
        redundancyK,
        fragmentHashes,
        List.of());
  }

  /**
   * Creates manifest with explicit per-block layout.
   *
   * <p>{@code blocks} non-empty signals a multi-block file: each entry describes one RS block of
   * the original file, in order. The {@code fragmentHashes} list at file level is left as the
   * legacy aggregate for retrocompat (callers may pass the concatenation of all per-block fragment
   * hashes, or a flat single-stripe view; downstream code consults {@code blocks()} when present
   * and falls back to the flat list otherwise). When {@code blocks} is empty, the manifest is
   * interpreted as legacy single-block.
   *
   * <p>Validation: if {@code blocks} is non-empty, the sum of {@code blockSizeBytes} must equal
   * {@code originalSizeBytes} and each block's {@code fragmentHashes.size()} must equal {@code
   * redundancyN}.
   */
  public FileManifest(
      final String fileId,
      final String directoryPath,
      final String originalFileName,
      final long originalSizeBytes,
      final Long compressedSizeBytes,
      final String compressionAlgorithm,
      final String originalFileHash,
      final int fragmentCount,
      final long fragmentSize,
      final int redundancyN,
      final int redundancyK,
      final List<String> fragmentHashes,
      final List<BlockManifest> blocks) {
    validateUuid(fileId);
    if (directoryPath == null || directoryPath.isBlank()) {
      throw new IllegalArgumentException("directoryPath must not be blank");
    }
    if (!DIRECTORY_PATH.matcher(directoryPath.trim()).matches()) {
      throw new IllegalArgumentException(
          "directoryPath must start with '/' and contain only valid path segments");
    }
    if (originalFileName == null || originalFileName.isBlank()) {
      throw new IllegalArgumentException("originalFileName must not be blank");
    }
    if (originalFileName.contains("/")) {
      throw new IllegalArgumentException("originalFileName must not contain path separators");
    }
    if (originalSizeBytes <= 0) {
      throw new IllegalArgumentException("originalSizeBytes must be greater than zero");
    }
    if (compressedSizeBytes != null && compressedSizeBytes <= 0) {
      throw new IllegalArgumentException(
          "compressedSizeBytes must be greater than zero when provided");
    }
    if (compressionAlgorithm != null && compressionAlgorithm.isBlank()) {
      throw new IllegalArgumentException("compressionAlgorithm must not be blank when provided");
    }
    if (originalFileHash == null || !SHA256_HEX.matcher(originalFileHash).matches()) {
      throw new IllegalArgumentException("originalFileHash must be a valid SHA-256 hex");
    }
    if (fragmentCount <= 0) {
      throw new IllegalArgumentException("fragmentCount must be greater than zero");
    }
    if (fragmentSize <= 0) {
      throw new IllegalArgumentException("fragmentSize must be greater than zero");
    }
    if (redundancyN <= 0 || redundancyK <= 0 || redundancyN < redundancyK) {
      throw new IllegalArgumentException("invalid redundancy values");
    }
    if (fragmentHashes == null) {
      throw new IllegalArgumentException("fragmentHashes must not be null");
    }
    if (fragmentHashes.stream()
        .anyMatch(hash -> hash == null || !SHA256_HEX.matcher(hash).matches())) {
      throw new IllegalArgumentException("fragmentHashes must contain valid SHA-256 hex values");
    }

    if (blocks == null) {
      throw new IllegalArgumentException("blocks must not be null (use empty list for legacy)");
    }
    if (!blocks.isEmpty()) {
      long sumBlockBytes = 0L;
      int expectedIndex = 0;
      for (BlockManifest block : blocks) {
        if (block == null) {
          throw new IllegalArgumentException("blocks must not contain null entries");
        }
        if (block.blockIndex() != expectedIndex) {
          throw new IllegalArgumentException(
              "blocks must be ordered with contiguous zero-based indices");
        }
        if (block.fragmentHashes().size() != redundancyN) {
          throw new IllegalArgumentException(
              "each block must declare exactly redundancyN fragment hashes");
        }
        sumBlockBytes += block.blockSizeBytes();
        expectedIndex++;
      }
      if (sumBlockBytes != originalSizeBytes) {
        throw new IllegalArgumentException("sum of block sizes must equal originalSizeBytes");
      }
    }

    this.fileId = fileId;
    this.directoryPath = directoryPath.trim();
    this.originalFileName = originalFileName.trim();
    this.originalSizeBytes = originalSizeBytes;
    this.compressedSizeBytes = compressedSizeBytes;
    this.compressionAlgorithm =
        compressionAlgorithm == null ? null : compressionAlgorithm.trim().toLowerCase(Locale.ROOT);
    this.originalFileHash = originalFileHash.toLowerCase(Locale.ROOT);
    this.fragmentCount = fragmentCount;
    this.fragmentSize = fragmentSize;
    this.redundancyN = redundancyN;
    this.redundancyK = redundancyK;
    this.fragmentHashes = List.copyOf(fragmentHashes);
    this.blocks = List.copyOf(blocks);
  }

  /**
   * Returns the per-block layout. Empty list signals legacy single-block manifests. Callers should
   * treat that as a single implicit block of {@link #originalSizeBytes} with this manifest's flat
   * {@link #fragmentHashes}.
   *
   * @return ordered immutable list of block manifests; empty for legacy
   */
  public List<BlockManifest> blocks() {
    return blocks;
  }

  /**
   * Returns file identifier.
   *
   * @return file identifier
   */
  public String fileId() {
    return fileId;
  }

  /**
   * Returns the logical directory path of the file in the originating node FS.
   *
   * @return directory path starting with {@code /}
   */
  public String directoryPath() {
    return directoryPath;
  }

  /**
   * Returns original file name.
   *
   * @return original file name
   */
  public String originalFileName() {
    return originalFileName;
  }

  /**
   * Returns original file size.
   *
   * @return original size in bytes
   */
  public long originalSizeBytes() {
    return originalSizeBytes;
  }

  /**
   * Returns compressed size when present.
   *
   * @return compressed size in bytes or null
   */
  public Long compressedSizeBytes() {
    return compressedSizeBytes;
  }

  /**
   * Returns compression algorithm when present.
   *
   * @return compression algorithm or null
   */
  public String compressionAlgorithm() {
    return compressionAlgorithm;
  }

  /**
   * Returns original file SHA-256 hash.
   *
   * @return file hash
   */
  public String originalFileHash() {
    return originalFileHash;
  }

  /**
   * Returns fragment count.
   *
   * @return fragment count
   */
  public int fragmentCount() {
    return fragmentCount;
  }

  /**
   * Returns fragment size.
   *
   * @return fragment size in bytes
   */
  public long fragmentSize() {
    return fragmentSize;
  }

  /**
   * Returns redundancy n value.
   *
   * @return redundancy n
   */
  public int redundancyN() {
    return redundancyN;
  }

  /**
   * Returns redundancy k value.
   *
   * @return redundancy k
   */
  public int redundancyK() {
    return redundancyK;
  }

  /**
   * Returns immutable fragment hash list.
   *
   * @return fragment hash list
   */
  public List<String> fragmentHashes() {
    return fragmentHashes;
  }

  private void validateUuid(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      throw new IllegalArgumentException("fileId must not be blank");
    }
    try {
      UUID.fromString(fileId);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("fileId must be a valid UUID");
    }
  }
}
