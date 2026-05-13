package es.ual.node.identitysecurity.adapters.in.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.identitysecurity.adapters.out.crypto.ECDSASignatureVerifier;
import es.ual.node.identitysecurity.adapters.out.memory.InMemoryNonceStore;
import es.ual.node.identitysecurity.adapters.out.memory.InMemoryPublicKeyRegistry;
import es.ual.node.identitysecurity.application.RequestSignatureValidationService;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests for {@link RequestSignatureValidator}. */
class RequestSignatureValidatorTest {

  private static final String DEFAULT_SIGNATURE_ALGORITHM = "SHA256withECDSA";
  private static final String NODE_ID = "node-a";
  private static final String NONCE = "nonce-123";
  private static final String METHOD = "POST";
  private static final String PATH = "/discovery";

  private KeyPair keyPair;
  private RequestSignatureValidator validator;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
    keyPairGenerator.initialize(256);
    keyPair = keyPairGenerator.generateKeyPair();

    InMemoryPublicKeyRegistry publicKeyRegistry = new InMemoryPublicKeyRegistry();
    publicKeyRegistry.register(NODE_ID, keyPair.getPublic());

    RequestSignatureValidationService service =
        new RequestSignatureValidationService(
            new ECDSASignatureVerifier(),
            publicKeyRegistry,
            new InMemoryNonceStore(),
            60,
            List.of(DEFAULT_SIGNATURE_ALGORITHM));

    validator = new RequestSignatureValidator(service, DEFAULT_SIGNATURE_ALGORITHM);
  }

  @Test
  void shouldAcceptRequestWithValidSignature() throws Exception {
    Instant now = Instant.now();
    MockHttpServletRequest request =
        buildRequest(
            now,
            sign(now, NONCE, METHOD, PATH, null, DEFAULT_SIGNATURE_ALGORITHM, keyPair.getPrivate()),
            DEFAULT_SIGNATURE_ALGORITHM,
            null);
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = validator.preHandle(request, response, new Object());

    assertTrue(allowed);
  }

  @Test
  void shouldRejectRequestWithInvalidSignature() throws Exception {
    Instant now = Instant.now();
    MockHttpServletRequest request =
        buildRequest(now, "invalid-signature", DEFAULT_SIGNATURE_ALGORITHM, null);
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = validator.preHandle(request, response, new Object());

    assertFalse(allowed);
    assertTrue(response.getStatus() >= 400);
  }

  @Test
  void shouldRejectReplayAttackWhenNonceIsReused() throws Exception {
    Instant now = Instant.now();
    String signature =
        sign(now, NONCE, METHOD, PATH, null, DEFAULT_SIGNATURE_ALGORITHM, keyPair.getPrivate());
    MockHttpServletRequest firstRequest =
        buildRequest(now, signature, DEFAULT_SIGNATURE_ALGORITHM, null);
    MockHttpServletRequest replayRequest =
        buildRequest(now, signature, DEFAULT_SIGNATURE_ALGORITHM, null);
    MockHttpServletResponse firstResponse = new MockHttpServletResponse();
    MockHttpServletResponse replayResponse = new MockHttpServletResponse();

    boolean firstAllowed = validator.preHandle(firstRequest, firstResponse, new Object());
    boolean replayAllowed = validator.preHandle(replayRequest, replayResponse, new Object());

    assertTrue(firstAllowed);
    assertFalse(replayAllowed);
    assertTrue(replayResponse.getStatus() >= 400);
  }

  @Test
  void shouldAcceptRequestWithoutAlgorithmHeaderUsingConfiguredDefault() throws Exception {
    Instant now = Instant.now();
    String signature =
        sign(now, NONCE, METHOD, PATH, null, DEFAULT_SIGNATURE_ALGORITHM, keyPair.getPrivate());
    MockHttpServletRequest request = buildRequest(now, signature, null, null);
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = validator.preHandle(request, response, new Object());

    assertTrue(allowed);
  }

  @Test
  void shouldRejectRequestWithUnsupportedSignatureAlgorithm() throws Exception {
    Instant now = Instant.now();
    String signature =
        sign(now, NONCE, METHOD, PATH, null, DEFAULT_SIGNATURE_ALGORITHM, keyPair.getPrivate());
    MockHttpServletRequest request = buildRequest(now, signature, "SHA384withECDSA", null);
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = validator.preHandle(request, response, new Object());

    assertFalse(allowed);
    assertTrue(response.getStatus() >= 400);
  }

  @Test
  void shouldRejectRequestWhenCanonicalQueryIsTampered() throws Exception {
    Instant now = Instant.now();
    String signedQuery = "targetFailureDomain=corp%2Fa&maxCandidates=10";
    String tamperedQuery = "targetFailureDomain=corp%2Fa&maxCandidates=11";

    String signature =
        sign(
            now,
            NONCE,
            METHOD,
            PATH,
            signedQuery,
            DEFAULT_SIGNATURE_ALGORITHM,
            keyPair.getPrivate());
    MockHttpServletRequest request =
        buildRequest(now, signature, DEFAULT_SIGNATURE_ALGORITHM, tamperedQuery);
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = validator.preHandle(request, response, new Object());

    assertFalse(allowed);
    assertTrue(response.getStatus() >= 400);
  }

  private MockHttpServletRequest buildRequest(
      final Instant instant,
      final String signatureValue,
      final String algorithm,
      final String queryString) {
    MockHttpServletRequest request = new MockHttpServletRequest(METHOD, PATH);
    if (queryString != null) {
      request.setQueryString(queryString);
    }
    request.addHeader(RequestSignatureValidator.HEADER_NODE_ID, NODE_ID);
    request.addHeader(RequestSignatureValidator.HEADER_NONCE, NONCE);
    request.addHeader(
        RequestSignatureValidator.HEADER_TIMESTAMP, Long.toString(instant.getEpochSecond()));
    request.addHeader(RequestSignatureValidator.HEADER_SIGNATURE, signatureValue);
    if (algorithm != null) {
      request.addHeader(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, algorithm);
    }
    return request;
  }

  private String sign(
      final Instant timestamp,
      final String nonce,
      final String method,
      final String path,
      final String queryString,
      final String algorithm,
      final PrivateKey privateKey)
      throws Exception {
    String canonical =
        RequestSignatureValidationService.buildCanonicalPayload(
            method, path, queryString, nonce, timestamp.getEpochSecond());
    Signature signature = Signature.getInstance(algorithm);
    signature.initSign(privateKey);
    signature.update(canonical.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(signature.sign());
  }
}
