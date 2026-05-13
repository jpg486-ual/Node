package es.ual.node.identitysecurity.application;

import es.ual.node.identitysecurity.ports.out.NonceStore;
import es.ual.node.identitysecurity.ports.out.PublicKeyRegistry;
import es.ual.node.identitysecurity.ports.out.SignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Application service that validates request signatures and replay constraints. */
public class RequestSignatureValidationService {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(RequestSignatureValidationService.class);

  private final SignatureVerifier signatureVerifier;
  private final PublicKeyRegistry publicKeyRegistry;
  private final NonceStore nonceStore;
  private final long signatureWindowSeconds;
  private final Set<String> allowedAlgorithms;

  /**
   * Creates the request signature validation service.
   *
   * @param signatureVerifier signature verification port
   * @param publicKeyRegistry public key registry port
   * @param nonceStore nonce store port
   * @param signatureWindowSeconds allowed timestamp skew window in seconds
   * @param allowedAlgorithms allowlist of accepted signature algorithms
   */
  public RequestSignatureValidationService(
      final SignatureVerifier signatureVerifier,
      final PublicKeyRegistry publicKeyRegistry,
      final NonceStore nonceStore,
      final long signatureWindowSeconds,
      final Collection<String> allowedAlgorithms) {
    if (signatureVerifier == null || publicKeyRegistry == null || nonceStore == null) {
      throw new IllegalArgumentException("Dependencies must not be null");
    }
    if (signatureWindowSeconds <= 0) {
      throw new IllegalArgumentException("signatureWindowSeconds must be greater than zero");
    }
    if (allowedAlgorithms == null) {
      throw new IllegalArgumentException("allowedAlgorithms must not be null");
    }
    this.signatureVerifier = signatureVerifier;
    this.publicKeyRegistry = publicKeyRegistry;
    this.nonceStore = nonceStore;
    this.signatureWindowSeconds = signatureWindowSeconds;
    this.allowedAlgorithms =
        allowedAlgorithms.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .collect(Collectors.toUnmodifiableSet());
    if (this.allowedAlgorithms.isEmpty()) {
      throw new IllegalArgumentException("allowedAlgorithms must contain at least one value");
    }
  }

  /**
   * Validates signature, timestamp window and nonce replay protection.
   *
   * @param request request data used to validate signature
   */
  public void validate(final SignatureValidationRequest request) {
    if (request == null) {
      throw new SignatureValidationException("Request must not be null");
    }

    if (!publicKeyRegistry.isRegistered(request.nodeId())) {
      throw new SignatureValidationException("Node is not registered");
    }

    if (!allowedAlgorithms.contains(request.algorithm())) {
      throw new SignatureValidationException("Unsupported signature algorithm");
    }

    final Instant now = Instant.now();
    final long delta = Math.abs(now.getEpochSecond() - request.timestampEpochSeconds());
    if (delta > signatureWindowSeconds) {
      throw new SignatureValidationException(
          "Request timestamp is outside configured security window");
    }

    final Instant nonceExpiry =
        Instant.ofEpochSecond(request.timestampEpochSeconds() + signatureWindowSeconds);
    final boolean acceptedNonce = nonceStore.markIfAbsent(request.nonce(), nonceExpiry);
    if (!acceptedNonce) {
      throw new SignatureValidationException("Replay attack detected: nonce already used");
    }

    final String canonicalPayload =
        buildCanonicalPayload(
            request.httpMethod(),
            request.path(),
            request.queryString(),
            request.nonce(),
            request.timestampEpochSeconds());
    final byte[] payload = canonicalPayload.getBytes(StandardCharsets.UTF_8);

    final byte[] decodedSignature;
    try {
      decodedSignature = Base64.getDecoder().decode(request.signatureBase64());
    } catch (IllegalArgumentException ex) {
      throw new SignatureValidationException("Signature is not valid Base64", ex);
    }

    final boolean verified =
        signatureVerifier.verify(
            request.algorithm(),
            payload,
            decodedSignature,
            publicKeyRegistry
                .findByNodeId(request.nodeId())
                .orElseThrow(() -> new SignatureValidationException("Node public key not found")));

    if (!verified) {
      LOGGER
          .atWarn()
          .setMessage("Request signature verification failed")
          .addKeyValue("nodeId", request.nodeId())
          .addKeyValue("path", request.path())
          .addKeyValue("method", request.httpMethod())
          .log();
      throw new SignatureValidationException("Invalid request signature");
    }
  }

  /**
   * Builds a canonical payload for deterministic signature verification.
   *
   * @param method HTTP method
   * @param path request path
   * @param queryString request query string (without leading '?')
   * @param nonce nonce header value
   * @param timestampEpochSeconds timestamp header value
   * @return canonical payload string
   */
  public static String buildCanonicalPayload(
      final String method,
      final String path,
      final String queryString,
      final String nonce,
      final long timestampEpochSeconds) {
    final String normalizedMethod = normalizeRequired(method, "method").toUpperCase(Locale.ROOT);
    final String normalizedPath = normalizeRequired(path, "path");
    final String normalizedQuery = queryString == null ? "" : queryString.trim();
    final String normalizedNonce = normalizeRequired(nonce, "nonce");
    return normalizedMethod
        + "\n"
        + normalizedPath
        + "\n"
        + normalizedQuery
        + "\n"
        + normalizedNonce
        + "\n"
        + timestampEpochSeconds;
  }

  private static String normalizeRequired(final String value, final String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value.trim();
  }
}
