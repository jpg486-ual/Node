package es.ual.node.filesystem.adapters.out.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.negotiation.domain.FileManifest;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SignedHttpRemoteFileManifestStoreClient}. */
class SignedHttpRemoteFileManifestStoreClientTest {

  private HttpServer server;
  private String baseUrl;
  private RemoteFileManifestStorePort sut;
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    final NodeIdentityContext identity = nodeIdentity("origin-node");
    sut = new SignedHttpRemoteFileManifestStoreClient(identity, objectMapper, "SHA256withECDSA");
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
        () -> new SignedHttpRemoteFileManifestStoreClient(null, objectMapper, "SHA256withECDSA"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SignedHttpRemoteFileManifestStoreClient(identity, null, "SHA256withECDSA"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SignedHttpRemoteFileManifestStoreClient(identity, objectMapper, ""));
  }

  @Test
  void store_sendsSignedPostWithEmbeddedClientPlacementsJson() throws Exception {
    final AtomicInteger requests = new AtomicInteger();
    final AtomicReference<String> nodeIdHeader = new AtomicReference<>();
    final AtomicReference<String> signatureHeader = new AtomicReference<>();
    final AtomicReference<String> contentTypeHeader = new AtomicReference<>();
    final AtomicReference<byte[]> body = new AtomicReference<>();

    server.createContext(
        "/recovery/file-manifests",
        exchange -> {
          requests.incrementAndGet();
          nodeIdHeader.set(exchange.getRequestHeaders().getFirst("X-Node-Id"));
          signatureHeader.set(exchange.getRequestHeaders().getFirst("X-Signature"));
          contentTypeHeader.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          body.set(exchange.getRequestBody().readAllBytes());
          exchange.sendResponseHeaders(201, -1);
          exchange.close();
        });
    server.start();

    final FileManifest manifest = sampleManifest();
    final List<FragmentPlacement> placements = samplePlacements(manifest.fileId());

    sut.store(manifest, placements, baseUrl);

    assertEquals(1, requests.get());
    assertEquals("origin-node", nodeIdHeader.get());
    assertNotNull(signatureHeader.get());
    assertEquals("application/json", contentTypeHeader.get());

    final JsonNode parsed = objectMapper.readTree(body.get());
    assertEquals(manifest.fileId(), parsed.get("fileId").asText());
    assertEquals("origin-node", parsed.get("requesterNodeId").asText());
    assertEquals(manifest.directoryPath(), parsed.get("directoryPath").asText());
    assertEquals(manifest.originalFileName(), parsed.get("originalFileName").asText());

    final JsonNode placementsField = parsed.get("clientPlacementsJson");
    assertNotNull(placementsField, "clientPlacementsJson must be present");
    final JsonNode placementsArray = objectMapper.readTree(placementsField.asText());
    assertEquals(3, placementsArray.size(), "all 3 placements must be embedded");
    assertEquals("frag-0", placementsArray.get(0).get("fragmentId").asText());
    assertEquals("http://node1:8080", placementsArray.get(0).get("custodianBaseUrl").asText());
  }

  @Test
  void store_serializesEmptyPlacementsAsEmptyArray() throws Exception {
    final AtomicReference<byte[]> body = new AtomicReference<>();
    server.createContext(
        "/recovery/file-manifests",
        exchange -> {
          body.set(exchange.getRequestBody().readAllBytes());
          exchange.sendResponseHeaders(201, -1);
          exchange.close();
        });
    server.start();

    sut.store(sampleManifest(), List.of(), baseUrl);

    final JsonNode parsed = objectMapper.readTree(body.get());
    final String placementsJson = parsed.get("clientPlacementsJson").asText();
    assertEquals("[]", placementsJson);
  }

  @Test
  void store_throwsOnNon2xx() throws Exception {
    server.createContext(
        "/recovery/file-manifests",
        exchange -> {
          exchange.sendResponseHeaders(403, -1);
          exchange.close();
        });
    server.start();

    final IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> sut.store(sampleManifest(), List.of(), baseUrl));
    assertTrue(ex.getMessage().contains("403"));
  }

  @Test
  void store_throwsOnConflict() throws Exception {
    server.createContext(
        "/recovery/file-manifests",
        exchange -> {
          exchange.sendResponseHeaders(409, -1);
          exchange.close();
        });
    server.start();

    assertThrows(
        IllegalStateException.class, () -> sut.store(sampleManifest(), List.of(), baseUrl));
  }

  @Test
  void store_rejectsBlankInputs() {
    final FileManifest m = sampleManifest();
    assertThrows(IllegalArgumentException.class, () -> sut.store(null, List.of(), baseUrl));
    assertThrows(IllegalArgumentException.class, () -> sut.store(m, null, baseUrl));
    assertThrows(IllegalArgumentException.class, () -> sut.store(m, List.of(), ""));
    assertThrows(IllegalArgumentException.class, () -> sut.store(m, List.of(), null));
  }

  @Test
  void delete_sendsSignedDeleteWithFileIdInPath() throws Exception {
    final AtomicReference<String> requestUri = new AtomicReference<>();
    final AtomicReference<String> requestMethod = new AtomicReference<>();
    final AtomicReference<String> nodeIdHeader = new AtomicReference<>();

    server.createContext(
        "/recovery/file-manifests/",
        exchange -> {
          requestUri.set(exchange.getRequestURI().toString());
          requestMethod.set(exchange.getRequestMethod());
          nodeIdHeader.set(exchange.getRequestHeaders().getFirst("X-Node-Id"));
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();

    sut.delete("file-abc-123", baseUrl);

    assertEquals("DELETE", requestMethod.get());
    assertEquals("/recovery/file-manifests/file-abc-123", requestUri.get());
    assertEquals("origin-node", nodeIdHeader.get());
  }

  @Test
  void delete_treats404AsIdempotentSuccess() throws Exception {
    final AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/recovery/file-manifests/",
        exchange -> {
          requests.incrementAndGet();
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();

    // Should NOT throw
    sut.delete("missing-file", baseUrl);
    assertEquals(1, requests.get());
  }

  @Test
  void delete_throwsOnOtherErrors() throws Exception {
    server.createContext(
        "/recovery/file-manifests/",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.start();

    assertThrows(IllegalStateException.class, () -> sut.delete("file-x", baseUrl));
  }

  @Test
  void delete_rejectsBlankInputs() {
    assertThrows(IllegalArgumentException.class, () -> sut.delete("", baseUrl));
    assertThrows(IllegalArgumentException.class, () -> sut.delete(null, baseUrl));
    assertThrows(IllegalArgumentException.class, () -> sut.delete("file-x", ""));
    assertThrows(IllegalArgumentException.class, () -> sut.delete("file-x", null));
  }

  private static FileManifest sampleManifest() {
    final String fileId = UUID.randomUUID().toString();
    return new FileManifest(
        fileId,
        "/jose/photos",
        "vacation.jpg",
        1024L,
        null,
        null,
        "0".repeat(64),
        3,
        512L,
        3,
        2,
        List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)));
  }

  private static List<FragmentPlacement> samplePlacements(final String fileId) {
    final Instant now = Instant.now();
    return List.of(
        new FragmentPlacement(
            fileId,
            "frag-0",
            0,
            0,
            false,
            "node-1",
            "http://node1:8080",
            "agreement-1",
            "a".repeat(64),
            512L,
            now),
        new FragmentPlacement(
            fileId,
            "frag-1",
            0,
            1,
            false,
            "node-2",
            "http://node2:8080",
            "agreement-2",
            "b".repeat(64),
            512L,
            now),
        new FragmentPlacement(
            fileId,
            "frag-2",
            0,
            2,
            true,
            "node-3",
            "http://node3:8080",
            "agreement-3",
            "c".repeat(64),
            512L,
            now));
  }

  private static NodeIdentityContext nodeIdentity(final String nodeId) throws Exception {
    final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(256);
    final KeyPair keyPair = generator.generateKeyPair();
    return new NodeIdentityContext(nodeId, keyPair.getPublic(), keyPair.getPrivate());
  }
}
