package es.ual.node.filesystem.application;

/**
 * Thrown when the {@code client_fragment_placement} state for a {@code fileId} is structurally
 * inconsistent, typically multiple rows for the same {@code (blockIndex, fragmentIndex)}.
 *
 * <p>This represents a permanent error: the catalog itself is corrupt and reconstruct cannot
 * proceed. Callers must surface a 5xx (not retry to peers nor fall back silently).
 */
public class InconsistentFragmentPlacementException extends RuntimeException {

  private final String fileId;
  private final int blockIndex;
  private final int fragmentIndex;
  private final int count;

  /** Creates exception. */
  public InconsistentFragmentPlacementException(
      final String fileId, final int blockIndex, final int fragmentIndex, final int count) {
    super(
        "placement set inconsistent for fileId="
            + fileId
            + ": blockIndex="
            + blockIndex
            + " fragmentIndex="
            + fragmentIndex
            + " has "
            + count
            + " placements (expected 1)");
    this.fileId = fileId;
    this.blockIndex = blockIndex;
    this.fragmentIndex = fragmentIndex;
    this.count = count;
  }

  public String fileId() {
    return fileId;
  }

  public int blockIndex() {
    return blockIndex;
  }

  public int fragmentIndex() {
    return fragmentIndex;
  }

  public int count() {
    return count;
  }
}
