package es.ual.node.custodyliveness.adapters.out.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.bootstrap.observability.RequestCorrelationInterceptor;
import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.custodyliveness.domain.CustodyProbeRequest;
import es.ual.node.custodyliveness.domain.CustodyProbeResponse;
import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Unit tests for {@link SignedHttpRemoteCustodyProbeClient}. */
class SignedHttpRemoteCustodyProbeClientTest {

  private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

  private HttpServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void probeShouldPropagateDomainRequestIdHeader() throws Exception {
    final AtomicReference<String> requestIdHeader = new AtomicReference<>();
    final AtomicReference<String> nodeIdHeader = new AtomicReference<>();
    server.createContext(
        "/custody/liveness/probes",
        exchange -> {
          requestIdHeader.set(
              exchange
                  .getRequestHeaders()
                  .getFirst(RequestCorrelationInterceptor.HEADER_REQUEST_ID));
          nodeIdHeader.set(
              exchange.getRequestHeaders().getFirst(RequestSignatureValidator.HEADER_NODE_ID));

          exchange.getRequestBody().readAllBytes();
          final byte[] responseBody =
              successfulProbeResponse("remote-response-1").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBody.length);
          exchange.getResponseBody().write(responseBody);
          exchange.close();
        });
    server.start();

    final String baseUrl = "http://localhost:" + server.getAddress().getPort();
    final SignedHttpRemoteCustodyProbeClient client =
        new SignedHttpRemoteCustodyProbeClient(
            nodeIdentity("node-local"),
            new NodeTopologyProperties(),
            livenessProperties(),
            objectMapper(),
            SIGNATURE_ALGORITHM);

    final CustodyProbeResponse response = client.probe(baseUrl, probeRequest("req-domain-123"));

    assertEquals("req-domain-123", requestIdHeader.get());
    assertEquals("node-local", nodeIdHeader.get());
    assertEquals("remote-response-1", response.requestId());
  }

  @Test
  void probeShouldPropagateMdcRequestIdWhenDomainRequestIdIsMissing() throws Exception {
    final AtomicReference<String> requestIdHeader = new AtomicReference<>();
    server.createContext(
        "/custody/liveness/probes",
        exchange -> {
          requestIdHeader.set(
              exchange
                  .getRequestHeaders()
                  .getFirst(RequestCorrelationInterceptor.HEADER_REQUEST_ID));

          exchange.getRequestBody().readAllBytes();
          final byte[] responseBody =
              successfulProbeResponse("remote-response-2").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBody.length);
          exchange.getResponseBody().write(responseBody);
          exchange.close();
        });
    server.start();

    final String baseUrl = "http://localhost:" + server.getAddress().getPort();
    final SignedHttpRemoteCustodyProbeClient client =
        new SignedHttpRemoteCustodyProbeClient(
            nodeIdentity("node-local"),
            new NodeTopologyProperties(),
            livenessProperties(),
            objectMapper(),
            SIGNATURE_ALGORITHM);

    MDC.put(RequestCorrelationInterceptor.MDC_REQUEST_ID_KEY, "req-mdc-456");
    final CustodyProbeResponse response = client.probe(baseUrl, probeRequest(null));

    assertEquals("req-mdc-456", requestIdHeader.get());
    assertEquals("remote-response-2", response.requestId());
  }

  @Test
  void probeShouldGenerateRequestIdWhenDomainAndMdcAreMissing() throws Exception {
    final AtomicReference<String> requestIdHeader = new AtomicReference<>();
    server.createContext(
        "/custody/liveness/probes",
        exchange -> {
          requestIdHeader.set(
              exchange
                  .getRequestHeaders()
                  .getFirst(RequestCorrelationInterceptor.HEADER_REQUEST_ID));

          exchange.getRequestBody().readAllBytes();
          final byte[] responseBody =
              successfulProbeResponse("remote-response-3").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBody.length);
          exchange.getResponseBody().write(responseBody);
          exchange.close();
        });
    server.start();

    final String baseUrl = "http://localhost:" + server.getAddress().getPort();
    final SignedHttpRemoteCustodyProbeClient client =
        new SignedHttpRemoteCustodyProbeClient(
            nodeIdentity("node-local"),
            new NodeTopologyProperties(),
            livenessProperties(),
            objectMapper(),
            SIGNATURE_ALGORITHM);

    final CustodyProbeResponse response = client.probe(baseUrl, probeRequest(null));

    assertNotNull(requestIdHeader.get());
    assertFalse(requestIdHeader.get().isBlank());
    assertEquals("remote-response-3", response.requestId());
  }

  private static CustodyLivenessProperties livenessProperties() {
    final CustodyLivenessProperties properties = new CustodyLivenessProperties();
    properties.setRequestTimeoutMillis(2000L);
    return properties;
  }

  private static CustodyProbeRequest probeRequest(final String requestId) {
    return CustodyProbeRequest.withoutRequesterTutor(
        requestId,
        "node-local",
        "node-remote",
        List.of(new CustodyProbeFragment("frag-1", "agreement-1", "checksum-1", 128L)),
        Instant.parse("2026-04-20T10:00:00Z"),
        120L);
  }

  private static String successfulProbeResponse(final String requestId) {
    return """
    {
      "requestId": "%s",
      "stillRequiredFragmentIds": ["frag-1"],
      "releasableFragmentIds": [],
      "reverseProbeRequested": false,
      "respondedAt": "2026-04-20T10:00:01Z"
    }
    """
        .formatted(requestId);
  }

  private static ObjectMapper objectMapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule());
  }

  private static NodeIdentityContext nodeIdentity(final String nodeId) throws Exception {
    final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
    keyPairGenerator.initialize(256);
    final KeyPair keyPair = keyPairGenerator.generateKeyPair();
    return new NodeIdentityContext(nodeId, keyPair.getPublic(), keyPair.getPrivate());
  }
}
