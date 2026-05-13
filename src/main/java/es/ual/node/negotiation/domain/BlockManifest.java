package es.ual.node.negotiation.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Manifest entry describing a single Reed-Solomon block within a file. The file is split into
 * fixed-size blocks at upload time; each block produces its own {@code n} fragments via the RS
 * codec and is recovered independently at download time. Hashes are SHA-256 hex (lowercase).
 *
 * <p>{@link FileManifest} aggregates these blocks in order of {@code blockIndex}. Reconstructing
 * the file means iterating blocks 0..N-1, fetching {@code k} fragments per block, RS-decoding, and
 * concatenating the resulting bytes.
 *
 * @param blockIndex zero-based position of the block in the original file
 * @param blockSizeBytes original (pre-RS-padding) size of the block in bytes. The last block may be
 *     smaller than {@code node.reedsolomon.block-size-bytes}
 * @param blockHash SHA-256 hex of the original block bytes (used to verify integrity after
 *     RS-reconstruction)
 * @param fragmentHashes SHA-256 hex of each of the {@code n} RS fragments produced from this block
 */
public record BlockManifest(
    int blockIndex, long blockSizeBytes, String blockHash, List<String> fragmentHashes) {

  private static final Pattern SHA256_HEX = Pattern.compile("^[a-fA-F0-9]{64}$");

  public BlockManifest {
    if (blockIndex < 0) {
      throw new IllegalArgumentException("blockIndex must not be negative");
    }
    if (blockSizeBytes <= 0) {
      throw new IllegalArgumentException("blockSizeBytes must be greater than zero");
    }
    if (blockHash == null || !SHA256_HEX.matcher(blockHash).matches()) {
      throw new IllegalArgumentException("blockHash must be a valid SHA-256 hex");
    }
    if (fragmentHashes == null || fragmentHashes.isEmpty()) {
      throw new IllegalArgumentException("fragmentHashes must not be empty");
    }
    if (fragmentHashes.stream()
        .anyMatch(hash -> hash == null || !SHA256_HEX.matcher(hash).matches())) {
      throw new IllegalArgumentException("fragmentHashes must contain valid SHA-256 hex values");
    }
    blockHash = blockHash.toLowerCase(java.util.Locale.ROOT);
    fragmentHashes = List.copyOf(fragmentHashes);
  }
}
