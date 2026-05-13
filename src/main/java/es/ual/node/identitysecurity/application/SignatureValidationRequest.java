package es.ual.node.identitysecurity.application;

import java.util.Objects;

/** Immutable request data used by signature validation use case. */
public final class SignatureValidationRequest {

  private final String nodeId;
  private final String nonce;
  private final long timestampEpochSeconds;
  private final String signatureBase64;
  private final String algorithm;
  private final String httpMethod;
  private final String path;
  private final String queryString;

  /**
   * Creates validated request data.
   *
   * @param nodeId node identifier
   * @param nonce nonce value
   * @param timestampEpochSeconds timestamp in epoch seconds
   * @param signatureBase64 base64 signature value
   * @param algorithm signature algorithm
   * @param httpMethod HTTP method
   * @param path request path
   * @param queryString request query string (without leading '?')
   */
  public SignatureValidationRequest(
      final String nodeId,
      final String nonce,
      final long timestampEpochSeconds,
      final String signatureBase64,
      final String algorithm,
      final String httpMethod,
      final String path,
      final String queryString) {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (nonce == null || nonce.isBlank()) {
      throw new IllegalArgumentException("nonce must not be blank");
    }
    if (signatureBase64 == null || signatureBase64.isBlank()) {
      throw new IllegalArgumentException("signatureBase64 must not be blank");
    }
    if (algorithm == null || algorithm.isBlank()) {
      throw new IllegalArgumentException("algorithm must not be blank");
    }
    if (httpMethod == null || httpMethod.isBlank()) {
      throw new IllegalArgumentException("httpMethod must not be blank");
    }
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path must not be blank");
    }

    this.nodeId = nodeId.trim();
    this.nonce = nonce.trim();
    this.timestampEpochSeconds = timestampEpochSeconds;
    this.signatureBase64 = signatureBase64.trim();
    this.algorithm = algorithm.trim();
    this.httpMethod = httpMethod.trim();
    this.path = path.trim();
    this.queryString = queryString == null ? "" : queryString.trim();
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
   * Returns nonce value.
   *
   * @return nonce value
   */
  public String nonce() {
    return nonce;
  }

  /**
   * Returns timestamp in epoch seconds.
   *
   * @return timestamp in epoch seconds
   */
  public long timestampEpochSeconds() {
    return timestampEpochSeconds;
  }

  /**
   * Returns base64 signature.
   *
   * @return base64 signature
   */
  public String signatureBase64() {
    return signatureBase64;
  }

  /**
   * Returns signature algorithm.
   *
   * @return signature algorithm
   */
  public String algorithm() {
    return algorithm;
  }

  /**
   * Returns HTTP method.
   *
   * @return HTTP method
   */
  public String httpMethod() {
    return httpMethod;
  }

  /**
   * Returns request path.
   *
   * @return request path
   */
  public String path() {
    return path;
  }

  /**
   * Returns request query string (without leading '?').
   *
   * @return request query string or empty string
   */
  public String queryString() {
    return queryString;
  }

  /**
   * Compares this request with another object.
   *
   * @param o object to compare
   * @return {@code true} when both requests are equal
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SignatureValidationRequest that)) {
      return false;
    }
    return timestampEpochSeconds == that.timestampEpochSeconds
        && Objects.equals(nodeId, that.nodeId)
        && Objects.equals(nonce, that.nonce)
        && Objects.equals(signatureBase64, that.signatureBase64)
        && Objects.equals(algorithm, that.algorithm)
        && Objects.equals(httpMethod, that.httpMethod)
        && Objects.equals(path, that.path)
        && Objects.equals(queryString, that.queryString);
  }

  /**
   * Returns hash code for this request.
   *
   * @return hash code
   */
  @Override
  public int hashCode() {
    return Objects.hash(
        nodeId,
        nonce,
        timestampEpochSeconds,
        signatureBase64,
        algorithm,
        httpMethod,
        path,
        queryString);
  }
}
