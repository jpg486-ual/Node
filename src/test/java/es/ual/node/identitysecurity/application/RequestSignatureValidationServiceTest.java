package es.ual.node.identitysecurity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.identitysecurity.adapters.out.memory.InMemoryNonceStore;
import es.ual.node.identitysecurity.adapters.out.memory.InMemoryPublicKeyRegistry;
import es.ual.node.identitysecurity.ports.out.SignatureVerifier;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests para {@link RequestSignatureValidationService}.
 *
 * <p>Tests aislados sin Spring: ejercitan las 8 ramas de fallo del corazón criptográfico inter-nodo
 * + el determinismo del canonical payload. Usan {@link InMemoryPublicKeyRegistry} y {@link
 * InMemoryNonceStore} reales (Cohn 2009 ch. 16) y dobles inline para {@link SignatureVerifier}
 * (Beck 2002 ch. 14 "Test Doubles").
 */
class RequestSignatureValidationServiceTest {

  private static final String NODE_ID = "node-a";
  private static final String ALGORITHM = "SHA256withECDSA";
  private static final long WINDOW_SECONDS = 60L;

  private static final SignatureVerifier OK_VERIFIER =
      (algorithm, payload, signature, publicKey) -> true;

  private InMemoryPublicKeyRegistry registry;
  private InMemoryNonceStore nonceStore;
  private KeyPair keyPair;

  @BeforeEach
  void setUp() throws NoSuchAlgorithmException, InvalidParameterSpecException {
    registry = new InMemoryPublicKeyRegistry();
    nonceStore = new InMemoryNonceStore();
    keyPair = generateEcKeyPair();
    registry.register(NODE_ID, keyPair.getPublic());
  }

  @Test
  void validate_succeedsWithRegisteredNodeAndValidWindow() {
    RequestSignatureValidationService service = serviceWith(OK_VERIFIER);
    SignatureValidationRequest request =
        request(NODE_ID, ALGORITHM, "nonce-1", Instant.now().getEpochSecond());

    service.validate(request);

    assertTrue(true, "validate completed without throwing");
  }

  @Test
  void validate_throwsWhenRequestIsNull() {
    RequestSignatureValidationService service = serviceWith(OK_VERIFIER);

    SignatureValidationException ex =
        assertThrows(SignatureValidationException.class, () -> service.validate(null));

    assertTrue(ex.getMessage().toLowerCase().contains("must not be null"));
  }

  @Test
  void validate_throwsWhenNodeNotRegistered() {
    RequestSignatureValidationService service = serviceWith(OK_VERIFIER);
    SignatureValidationRequest request =
        request("unknown-node", ALGORITHM, "nonce-2", Instant.now().getEpochSecond());

    SignatureValidationException ex =
        assertThrows(SignatureValidationException.class, () -> service.validate(request));

    assertTrue(ex.getMessage().toLowerCase().contains("not registered"));
  }

  @Test
  void validate_throwsWhenAlgorithmNotAllowed() {
    RequestSignatureValidationService service = serviceWith(OK_VERIFIER);
    SignatureValidationRequest request =
        request(NODE_ID, "MD5", "nonce-3", Instant.now().getEpochSecond());

    SignatureValidationException ex =
        assertThrows(SignatureValidationException.class, () -> service.validate(request));

    assertTrue(ex.getMessage().toLowerCase().contains("unsupported"));
  }

  @Test
  void validate_throwsWhenTimestampInPastOutsideWindow() {
    RequestSignatureValidationService service = serviceWith(OK_VERIFIER);
    long past = Instant.now().getEpochSecond() - (WINDOW_SECONDS + 60);
    SignatureValidationRequest request = request(NODE_ID, ALGORITHM, "nonce-4", past);

    SignatureValidationException ex =
        assertThrows(SignatureValidationException.class, () -> service.validate(request));

    assertTrue(ex.getMessage().toLowerCase().contains("timestamp"));
  }

  @Test
  void validate_throwsWhenTimestampInFutureOutsideWindow() {
    RequestSignatureValidationService service = serviceWith(OK_VERIFIER);
    long future = Instant.now().getEpochSecond() + (WINDOW_SECONDS + 60);
    SignatureValidationRequest request = request(NODE_ID, ALGORITHM, "nonce-5", future);

    SignatureValidationException ex =
        assertThrows(SignatureValidationException.class, () -> service.validate(request));

    assertTrue(ex.getMessage().toLowerCase().contains("timestamp"));
  }

  @Test
  void validate_throwsWhenNonceReplayed() {
    RequestSignatureValidationService service = serviceWith(OK_VERIFIER);
    long now = Instant.now().getEpochSecond();
    SignatureValidationRequest first = request(NODE_ID, ALGORITHM, "replay-nonce", now);
    SignatureValidationRequest second = request(NODE_ID, ALGORITHM, "replay-nonce", now);

    service.validate(first);

    SignatureValidationException ex =
        assertThrows(SignatureValidationException.class, () -> service.validate(second));

    assertTrue(ex.getMessage().toLowerCase().contains("replay"));
  }

  @Test
  void validate_throwsWhenSignatureFails() {
    AtomicInteger calls = new AtomicInteger();
    SignatureVerifier observingReject =
        (algorithm, payload, signature, publicKey) -> {
          calls.incrementAndGet();
          return false;
        };
    RequestSignatureValidationService service = serviceWith(observingReject);
    SignatureValidationRequest request =
        request(NODE_ID, ALGORITHM, "nonce-7", Instant.now().getEpochSecond());

    SignatureValidationException ex =
        assertThrows(SignatureValidationException.class, () -> service.validate(request));

    assertTrue(ex.getMessage().toLowerCase().contains("invalid"));
    assertEquals(1, calls.get(), "verifier was invoked exactly once");
  }

  @Test
  void validate_throwsWhenSignatureBase64Malformed() {
    RequestSignatureValidationService service = serviceWith(OK_VERIFIER);
    SignatureValidationRequest request =
        new SignatureValidationRequest(
            NODE_ID,
            "nonce-8",
            Instant.now().getEpochSecond(),
            "***INVALID-BASE64***",
            ALGORITHM,
            "GET",
            "/ops/health",
            "");

    SignatureValidationException ex =
        assertThrows(SignatureValidationException.class, () -> service.validate(request));

    assertTrue(ex.getMessage().toLowerCase().contains("base64"));
  }

  @Test
  void buildCanonicalPayload_isDeterministicAndCanonical() {
    String first =
        RequestSignatureValidationService.buildCanonicalPayload(
            "post", "/ops/custody-liveness/probe", "nodeId=alice", "abc-123", 1700000000L);
    String second =
        RequestSignatureValidationService.buildCanonicalPayload(
            "post", "/ops/custody-liveness/probe", "nodeId=alice", "abc-123", 1700000000L);

    assertEquals(first, second, "canonical payload must be deterministic");

    String expected = "POST\n/ops/custody-liveness/probe\nnodeId=alice\nabc-123\n1700000000";
    assertEquals(
        expected, first, "canonical payload must follow METHOD\\nPATH\\nQUERY\\nNONCE\\nTS");

    String trimmed =
        RequestSignatureValidationService.buildCanonicalPayload(
            "  GET  ", "  /fs  ", null, "  nonce  ", 42L);
    assertEquals("GET\n/fs\n\nnonce\n42", trimmed, "fields trimmed and null query coerced to ''");
  }

  private RequestSignatureValidationService serviceWith(final SignatureVerifier verifier) {
    return new RequestSignatureValidationService(
        verifier, registry, nonceStore, WINDOW_SECONDS, Set.of(ALGORITHM));
  }

  private SignatureValidationRequest request(
      final String nodeId,
      final String algorithm,
      final String nonce,
      final long timestampEpochSeconds) {
    return new SignatureValidationRequest(
        nodeId, nonce, timestampEpochSeconds, "MEUCIQDxxx==", algorithm, "GET", "/ops/health", "");
  }

  private static KeyPair generateEcKeyPair()
      throws NoSuchAlgorithmException, InvalidParameterSpecException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    try {
      generator.initialize(new ECGenParameterSpec("secp256r1"));
    } catch (java.security.InvalidAlgorithmParameterException ex) {
      throw new InvalidParameterSpecException(ex.getMessage());
    }
    return generator.generateKeyPair();
  }
}
