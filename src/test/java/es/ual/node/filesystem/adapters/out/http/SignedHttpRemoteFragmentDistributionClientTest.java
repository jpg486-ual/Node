package es.ual.node.filesystem.adapters.out.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import es.ual.node.filesystem.ports.out.RemoteFragmentDistributionClientPort;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SignedHttpRemoteFragmentDistributionClient}. */
class SignedHttpRemoteFragmentDistributionClientTest {

  private HttpServer server;
  private String baseUrl;
  private RemoteFragmentDistributionClientPort sut;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    final NodeIdentityContext identity = nodeIdentity("origin-node");
    sut = new SignedHttpRemoteFragmentDistributionClient(identity, "SHA256withECDSA");
    baseUrl = "http://localhost:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void rejectsInvalidConstruction() throws Exception {
    final NodeIdentityContext identity = nodeIdentity("n");
    assertThrows(
        IllegalArgumentException.class,
        () -> new SignedHttpRemoteFragmentDistributionClient(null, "SHA256withECDSA"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SignedHttpRemoteFragmentDistributionClient(identity, ""));
  }

  @Test
  void storeFragmentSendsSignedPostWithExpectedHeaders() throws Exception {
    final AtomicInteger requests = new AtomicInteger();
    final AtomicReference<String> fragmentIdHeader = new AtomicReference<>();
    final AtomicReference<String> agreementIdHeader = new AtomicReference<>();
    final AtomicReference<String> nodeIdHeader = new AtomicReference<>();
    final AtomicReference<String> signatureHeader = new AtomicReference<>();
    final AtomicReference<String> publicKeyHeader = new AtomicReference<>();
    final AtomicReference<String> checksumHeader = new AtomicReference<>();
    final AtomicReference<byte[]> body = new AtomicReference<>();

    server.createContext(
        "/custody/fragments",
        exchange -> {
          requests.incrementAndGet();
          fragmentIdHeader.set(exchange.getRequestHeaders().getFirst("X-Fragment-Id"));
          agreementIdHeader.set(exchange.getRequestHeaders().getFirst("X-Agreement-Id"));
          nodeIdHeader.set(exchange.getRequestHeaders().getFirst("X-Node-Id"));
          signatureHeader.set(exchange.getRequestHeaders().getFirst("X-Signature"));
          publicKeyHeader.set(exchange.getRequestHeaders().getFirst("X-Sender-Public-Key"));
          checksumHeader.set(exchange.getRequestHeaders().getFirst("X-Checksum"));
          body.set(exchange.getRequestBody().readAllBytes());
          exchange.sendResponseHeaders(201, -1);
          exchange.close();
        });
    server.start();

    final byte[] payload = "fragment-bytes".getBytes();
    sut.storeFragment(baseUrl, "frag-1", "agreement-1", payload, "SHA-256", "abcd", null);

    assertEquals(1, requests.get());
    assertEquals("frag-1", fragmentIdHeader.get());
    assertEquals("agreement-1", agreementIdHeader.get());
    assertEquals("origin-node", nodeIdHeader.get());
    assertNotNull(signatureHeader.get());
    assertNotNull(publicKeyHeader.get());
    assertEquals("abcd", checksumHeader.get());
    assertArrayEquals(payload, body.get());
  }

  @Test
  void storeFragmentThrowsOnNon2xx() throws Exception {
    server.createContext(
        "/custody/fragments",
        exchange -> {
          exchange.sendResponseHeaders(403, -1);
          exchange.close();
        });
    server.start();

    assertThrows(
        IllegalStateException.class,
        () ->
            sut.storeFragment(
                baseUrl, "frag-1", "agreement-1", new byte[] {1}, "SHA-256", "abcd", null));
  }

  @Test
  void storeFragmentThrowsCustodianInsufficientStorageOn507() throws Exception {
    server.createContext(
        "/custody/fragments",
        exchange -> {
          final byte[] body = "{\"errorCode\":\"CUSTODY_INSUFFICIENT_STORAGE\"}".getBytes();
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(507, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();

    final es.ual.node.filesystem.application.CustodianInsufficientStorageException ex =
        assertThrows(
            es.ual.node.filesystem.application.CustodianInsufficientStorageException.class,
            () ->
                sut.storeFragment(
                    baseUrl, "frag-1", "agreement-1", new byte[] {1}, "SHA-256", "abcd", null));
    assertEquals(baseUrl, ex.custodianBaseUrl());
  }

  @Test
  void fetchFragmentReturnsBytes() throws Exception {
    final byte[] expected = "stored-bytes".getBytes();
    server.createContext(
        "/custody/fragments/frag-1/content",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
          exchange.sendResponseHeaders(200, expected.length);
          exchange.getResponseBody().write(expected);
          exchange.close();
        });
    server.start();

    final byte[] retrieved = sut.fetchFragment(baseUrl, "frag-1");

    assertArrayEquals(expected, retrieved);
  }

  @Test
  void fetchFragmentThrowsOnNotFound() throws Exception {
    server.createContext(
        "/custody/fragments/missing/content",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();

    assertThrows(IllegalStateException.class, () -> sut.fetchFragment(baseUrl, "missing"));
  }

  @Test
  void rejectsBlankInputs() {
    assertThrows(
        IllegalArgumentException.class,
        () -> sut.storeFragment("", "f", "a", new byte[] {1}, "SHA-256", "h", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> sut.storeFragment(baseUrl, "", "a", new byte[] {1}, "SHA-256", "h", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> sut.storeFragment(baseUrl, "f", "a", new byte[0], "SHA-256", "h", null));
    assertThrows(IllegalArgumentException.class, () -> sut.fetchFragment("", "f"));
    assertThrows(IllegalArgumentException.class, () -> sut.fetchFragment(baseUrl, ""));
  }

  private static NodeIdentityContext nodeIdentity(final String nodeId) throws Exception {
    final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(256);
    final KeyPair keyPair = generator.generateKeyPair();
    return new NodeIdentityContext(nodeId, keyPair.getPublic(), keyPair.getPrivate());
  }
}
