package es.ual.node.negotiation.domain;

import java.util.regex.Pattern;

/** Immutable request to initiate a negotiation agreement. */
public final class NegotiationCreateRequest {

  private static final Pattern HTTP_URL =
      Pattern.compile("^https?://[a-zA-Z0-9._-]+(:\\d+)?(/.*)?$");

  private final String requesterNodeId;
  private final String targetNodeId;
  private final long bucketSize;
  private final long expectedStorageBytes;
  private final TransferMode transferMode;
  private final Integer fragmentCount;
  private final String redundancyScheme;
  private final int expirationSeconds;
  private final FileManifest fileManifest;
  private final String requesterSignature;
  private final String requesterTutorNodeId;
  private final String requesterTutorBaseUrl;

  /**
   * Convenience constructor without requester tutor info. Delegates to the canonical constructor
   * with {@code null} tutor fields.
   *
   * @param requesterNodeId requester node id
   * @param targetNodeId target node id
   * @param bucketSize bucket size in bytes
   * @param expectedStorageBytes expected storage in bytes
   * @param transferMode transfer mode
   * @param fragmentCount optional fragment count
   * @param redundancyScheme redundancy scheme description
   * @param expirationSeconds requested expiration in seconds
   * @param fileManifest optional file manifest
   * @param requesterSignature requester signature over request payload
   */
  public NegotiationCreateRequest(
      final String requesterNodeId,
      final String targetNodeId,
      final long bucketSize,
      final long expectedStorageBytes,
      final TransferMode transferMode,
      final Integer fragmentCount,
      final String redundancyScheme,
      final int expirationSeconds,
      final FileManifest fileManifest,
      final String requesterSignature) {
    this(
        requesterNodeId,
        targetNodeId,
        bucketSize,
        expectedStorageBytes,
        transferMode,
        fragmentCount,
        redundancyScheme,
        expirationSeconds,
        fileManifest,
        requesterSignature,
        null,
        null);
  }

  /**
   * Creates a validated negotiation creation request.
   *
   * @param requesterNodeId requester node id
   * @param targetNodeId target node id
   * @param bucketSize bucket size in bytes
   * @param expectedStorageBytes expected storage in bytes
   * @param transferMode transfer mode
   * @param fragmentCount optional fragment count
   * @param redundancyScheme redundancy scheme description
   * @param expirationSeconds requested expiration in seconds
   * @param fileManifest optional file manifest
   * @param requesterSignature requester signature over request payload
   * @param requesterTutorNodeId optional tutor node id of the requester (propagated to enable
   *     correct return-to-tutor escalation when this peer custodies fragments)
   * @param requesterTutorBaseUrl optional tutor base URL of the requester (propagated to enable
   *     correct return-to-tutor escalation when this peer custodies fragments)
   */
  public NegotiationCreateRequest(
      final String requesterNodeId,
      final String targetNodeId,
      final long bucketSize,
      final long expectedStorageBytes,
      final TransferMode transferMode,
      final Integer fragmentCount,
      final String redundancyScheme,
      final int expirationSeconds,
      final FileManifest fileManifest,
      final String requesterSignature,
      final String requesterTutorNodeId,
      final String requesterTutorBaseUrl) {
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      throw new IllegalArgumentException("requesterNodeId must not be blank");
    }
    if (targetNodeId == null || targetNodeId.isBlank()) {
      throw new IllegalArgumentException("targetNodeId must not be blank");
    }
    if (bucketSize <= 0) {
      throw new IllegalArgumentException("bucketSize must be greater than zero");
    }
    if (expectedStorageBytes <= 0) {
      throw new IllegalArgumentException("expectedStorageBytes must be greater than zero");
    }
    if (transferMode == null) {
      throw new IllegalArgumentException("transferMode must not be null");
    }
    if (expirationSeconds <= 0) {
      throw new IllegalArgumentException("expirationSeconds must be greater than zero");
    }
    if (requesterSignature == null || requesterSignature.isBlank()) {
      throw new IllegalArgumentException("requesterSignature must not be blank");
    }
    if (redundancyScheme != null && redundancyScheme.isBlank()) {
      throw new IllegalArgumentException("redundancyScheme must not be blank when provided");
    }
    if (requesterTutorNodeId != null && requesterTutorNodeId.isBlank()) {
      throw new IllegalArgumentException("requesterTutorNodeId must not be blank when provided");
    }
    if (requesterTutorBaseUrl != null) {
      if (requesterTutorBaseUrl.isBlank()) {
        throw new IllegalArgumentException("requesterTutorBaseUrl must not be blank when provided");
      }
      if (!HTTP_URL.matcher(requesterTutorBaseUrl.trim()).matches()) {
        throw new IllegalArgumentException("requesterTutorBaseUrl must be an http(s) URL");
      }
    }

    this.requesterNodeId = requesterNodeId.trim();
    this.targetNodeId = targetNodeId.trim();
    this.bucketSize = bucketSize;
    this.expectedStorageBytes = expectedStorageBytes;
    this.transferMode = transferMode;
    this.fragmentCount = fragmentCount;
    this.redundancyScheme = redundancyScheme == null ? null : redundancyScheme.trim();
    this.expirationSeconds = expirationSeconds;
    this.fileManifest = fileManifest;
    this.requesterSignature = requesterSignature.trim();
    this.requesterTutorNodeId = requesterTutorNodeId == null ? null : requesterTutorNodeId.trim();
    this.requesterTutorBaseUrl =
        requesterTutorBaseUrl == null ? null : requesterTutorBaseUrl.trim();
  }

  /**
   * Returns requester node id.
   *
   * @return requester node id
   */
  public String requesterNodeId() {
    return requesterNodeId;
  }

  /**
   * Returns target node id.
   *
   * @return target node id
   */
  public String targetNodeId() {
    return targetNodeId;
  }

  /**
   * Returns bucket size.
   *
   * @return bucket size in bytes
   */
  public long bucketSize() {
    return bucketSize;
  }

  /**
   * Returns expected storage bytes.
   *
   * @return expected storage bytes
   */
  public long expectedStorageBytes() {
    return expectedStorageBytes;
  }

  /**
   * Returns transfer mode.
   *
   * @return transfer mode
   */
  public TransferMode transferMode() {
    return transferMode;
  }

  /**
   * Returns fragment count.
   *
   * @return fragment count when provided
   */
  public Integer fragmentCount() {
    return fragmentCount;
  }

  /**
   * Returns redundancy scheme string.
   *
   * @return redundancy scheme
   */
  public String redundancyScheme() {
    return redundancyScheme;
  }

  /**
   * Returns requested expiration seconds.
   *
   * @return expiration seconds
   */
  public int expirationSeconds() {
    return expirationSeconds;
  }

  /**
   * Returns file manifest when present.
   *
   * @return file manifest or null
   */
  public FileManifest fileManifest() {
    return fileManifest;
  }

  /**
   * Returns requester signature.
   *
   * @return requester signature
   */
  public String requesterSignature() {
    return requesterSignature;
  }

  /**
   * Returns optional tutor node id of the requester.
   *
   * @return requester tutor node id or {@code null} when absent
   */
  public String requesterTutorNodeId() {
    return requesterTutorNodeId;
  }

  /**
   * Returns optional tutor base URL of the requester.
   *
   * @return requester tutor base URL or {@code null} when absent
   */
  public String requesterTutorBaseUrl() {
    return requesterTutorBaseUrl;
  }
}
