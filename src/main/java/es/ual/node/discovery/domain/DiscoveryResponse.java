package es.ual.node.discovery.domain;

import java.util.List;
import java.util.Objects;

/** Immutable discovery response containing candidate nodes. */
public final class DiscoveryResponse {

  private final List<CandidateNode> candidates;

  /**
   * Creates a discovery response.
   *
   * @param candidates discovered candidate nodes
   */
  public DiscoveryResponse(final List<CandidateNode> candidates) {
    if (candidates == null) {
      throw new IllegalArgumentException("candidates must not be null");
    }
    this.candidates = List.copyOf(candidates);
  }

  /**
   * Returns immutable candidate list.
   *
   * @return candidates
   */
  public List<CandidateNode> candidates() {
    return candidates;
  }

  /** Immutable candidate node response element. */
  public static final class CandidateNode {

    private final String nodeId;
    private final String baseUrl;
    private final long originalBucketSize;

    /**
     * Creates candidate node response item.
     *
     * @param nodeId node identifier
     * @param baseUrl candidate HTTP base URL (required to resolve the candidate to a custodian URL
     *     without consulting topology config)
     * @param originalBucketSize candidate original requested bucket size
     */
    public CandidateNode(final String nodeId, final String baseUrl, final long originalBucketSize) {
      if (nodeId == null || nodeId.isBlank()) {
        throw new IllegalArgumentException("nodeId must not be blank");
      }
      if (baseUrl == null || baseUrl.isBlank()) {
        throw new IllegalArgumentException("baseUrl must not be blank");
      }
      if (originalBucketSize <= 0) {
        throw new IllegalArgumentException("originalBucketSize must be greater than zero");
      }
      this.nodeId = nodeId.trim();
      this.baseUrl = baseUrl.trim();
      this.originalBucketSize = originalBucketSize;
    }

    /**
     * Returns node identifier.
     *
     * @return node identifier
     */
    public String nodeId() {
      return nodeId;
    }

    /**
     * Returns candidate HTTP base URL.
     *
     * @return base URL
     */
    public String baseUrl() {
      return baseUrl;
    }

    /**
     * Returns original requested bucket size.
     *
     * @return original requested bucket size
     */
    public long originalBucketSize() {
      return originalBucketSize;
    }

    /**
     * Compares this candidate with another object.
     *
     * @param o object to compare
     * @return {@code true} when equal
     */
    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof CandidateNode that)) {
        return false;
      }
      return originalBucketSize == that.originalBucketSize
          && Objects.equals(nodeId, that.nodeId)
          && Objects.equals(baseUrl, that.baseUrl);
    }

    /**
     * Returns hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
      return Objects.hash(nodeId, baseUrl, originalBucketSize);
    }
  }
}
