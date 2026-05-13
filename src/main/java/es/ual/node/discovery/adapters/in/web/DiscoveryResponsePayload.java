package es.ual.node.discovery.adapters.in.web;

import java.util.List;

/** HTTP payload returned by discovery endpoint. */
public final class DiscoveryResponsePayload {

  private final List<CandidateNodePayload> candidates;
  private final String queuedRequestId;

  /**
   * Creates response payload.
   *
   * @param candidates candidate payload list
   */
  public DiscoveryResponsePayload(final List<CandidateNodePayload> candidates) {
    this(candidates, null);
  }

  /**
   * Creates response payload.
   *
   * @param candidates candidate payload list
   * @param queuedRequestId queued retry request id when no candidates were found
   */
  public DiscoveryResponsePayload(
      final List<CandidateNodePayload> candidates, final String queuedRequestId) {
    this.candidates = List.copyOf(candidates);
    this.queuedRequestId = queuedRequestId;
  }

  /**
   * Returns candidate payload list.
   *
   * @return candidate payload list
   */
  public List<CandidateNodePayload> candidates() {
    return candidates;
  }

  /**
   * Returns candidate payload list using JavaBean convention.
   *
   * @return candidate payload list
   */
  public List<CandidateNodePayload> getCandidates() {
    return candidates;
  }

  /**
   * Returns queued request id when response was persisted for retry.
   *
   * @return queued request id or null
   */
  public String queuedRequestId() {
    return queuedRequestId;
  }

  /**
   * Returns queued request id using JavaBean convention.
   *
   * @return queued request id or null
   */
  public String getQueuedRequestId() {
    return queuedRequestId;
  }

  /**
   * Candidate node payload item.
   *
   * <p>Incluye {@code baseUrl}, los nodos comunes consultan supernodos vía HTTP signed y necesitan
   * resolver {@code nodeId → baseUrl} para distribuir fragments.
   */
  public static final class CandidateNodePayload {

    private final String nodeId;
    private final long originalBucketSize;
    private final String baseUrl;

    /**
     * Creates candidate payload item.
     *
     * @param nodeId node identifier
     * @param originalBucketSize original bucket size
     * @param baseUrl candidate base URL (e.g. {@code http://node2:8080})
     */
    public CandidateNodePayload(
        final String nodeId, final long originalBucketSize, final String baseUrl) {
      this.nodeId = nodeId;
      this.originalBucketSize = originalBucketSize;
      this.baseUrl = baseUrl;
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
     * Returns node identifier using JavaBean convention.
     *
     * @return node identifier
     */
    public String getNodeId() {
      return nodeId;
    }

    /**
     * Returns original bucket size.
     *
     * @return original bucket size
     */
    public long originalBucketSize() {
      return originalBucketSize;
    }

    /**
     * Returns original bucket size using JavaBean convention.
     *
     * @return original bucket size
     */
    public long getOriginalBucketSize() {
      return originalBucketSize;
    }

    /**
     * Returns candidate base URL.
     *
     * @return base URL (e.g. {@code http://node2:8080})
     */
    public String baseUrl() {
      return baseUrl;
    }

    /**
     * Returns candidate base URL using JavaBean convention.
     *
     * @return base URL
     */
    public String getBaseUrl() {
      return baseUrl;
    }
  }
}
