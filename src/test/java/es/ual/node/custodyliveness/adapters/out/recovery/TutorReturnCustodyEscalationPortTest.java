package es.ual.node.custodyliveness.adapters.out.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sun.net.httpserver.HttpServer;
import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.bootstrap.observability.RequestCorrelationInterceptor;
import es.ual.node.custodyliveness.adapters.out.custody.CustodyFragmentLifecycleAdapter;
import es.ual.node.custodyliveness.application.CustodyLivenessObservabilityService;
import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import es.ual.node.custodyliveness.domain.CustodyEscalationPolicy;
import es.ual.node.custodyliveness.domain.CustodyProbeDirection;
import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import es.ual.node.fragmentstorage.adapters.out.memory.InMemoryCustodyFragmentPayloadPort;
import es.ual.node.fragmentstorage.adapters.out.memory.InMemoryCustodyFragmentPort;
import es.ual.node.fragmentstorage.domain.CustodyFragment;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Unit tests for {@link TutorReturnCustodyEscalationPort}. */
class TutorReturnCustodyEscalationPortTest {

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
  void returnToTutorTransfersFragmentAndDeletesLocalCustody() throws Exception {
    final AtomicInteger requests = new AtomicInteger(0);
    final AtomicReference<String> fragmentHeader = new AtomicReference<>();
    final AtomicReference<String> signatureHeader = new AtomicReference<>();
    final AtomicReference<String> requesterKeyHeader = new AtomicReference<>();
    final AtomicReference<String> requestIdHeader = new AtomicReference<>();
    server.createContext(
        "/recovery/fragments",
        exchange -> {
          requests.incrementAndGet();
          fragmentHeader.set(exchange.getRequestHeaders().getFirst("X-Fragment-Id"));
          signatureHeader.set(exchange.getRequestHeaders().getFirst("X-Signature"));
          requesterKeyHeader.set(exchange.getRequestHeaders().getFirst("X-Requester-Public-Key"));
          requestIdHeader.set(
              exchange
                  .getRequestHeaders()
                  .getFirst(RequestCorrelationInterceptor.HEADER_REQUEST_ID));

          final byte[] requestBody = exchange.getRequestBody().readAllBytes();
          if (requestBody.length == 0) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
          }

          exchange.sendResponseHeaders(201, -1);
          exchange.close();
        });
    server.start();

    final InMemoryCustodyFragmentPort custodyPort = new InMemoryCustodyFragmentPort();
    final InMemoryCustodyFragmentPayloadPort payloadPort = new InMemoryCustodyFragmentPayloadPort();

    final String fragmentId = "frag-return-1";
    final byte[] payload = "payload-to-return".getBytes();
    final Instant now = Instant.parse("2026-03-31T10:00:00Z");
    custodyPort.save(
        new CustodyFragment(
            fragmentId,
            "agreement-1",
            "node-remote",
            "SHA-256",
            "dummy-checksum",
            payload.length,
            now,
            now.plusSeconds(1200L)));
    payloadPort.save(fragmentId, payload);

    final NodeTopologyProperties topology = new NodeTopologyProperties();
    topology.setTutorBaseUrl("http://localhost:" + server.getAddress().getPort());

    final CustodyLivenessProperties properties = new CustodyLivenessProperties();
    properties.setRequestTimeoutMillis(2000L);

    final TutorReturnCustodyEscalationPort escalationPort =
        new TutorReturnCustodyEscalationPort(
            topology,
            nodeIdentity("node-local"),
            custodyPort,
            payloadPort,
            properties,
            new CustodyFragmentLifecycleAdapter(custodyPort, payloadPort),
            CustodyLivenessObservabilityService.noop(),
            "SHA256withECDSA");

    final CustodyProbeSession session =
        CustodyProbeSession.withoutRemoteTutor(
            "session-1",
            "node-remote",
            CustodyProbeDirection.OUTBOUND,
            es.ual.node.custodyliveness.domain.CustodyProbeStatus.UNRESPONSIVE,
            10,
            null,
            now,
            null,
            "timeout",
            null,
            now,
            now);

    escalationPort.handleUnresponsive(
        session,
        List.of(
            new CustodyProbeFragment(fragmentId, "agreement-1", "dummy-checksum", payload.length)),
        "timeout",
        now,
        CustodyEscalationPolicy.RETURN_TO_TUTOR);

    assertEquals(1, requests.get());
    assertEquals(fragmentId, fragmentHeader.get());
    assertNotNull(signatureHeader.get());
    assertNotNull(requesterKeyHeader.get());
    assertNotNull(requestIdHeader.get());
    assertFalse(custodyPort.findByFragmentId(fragmentId).isPresent());
    assertFalse(payloadPort.findByFragmentId(fragmentId).isPresent());
  }

  @Test
  void returnToTutorPrefersRemoteTutorFromSessionOverLocalConfig() throws Exception {
    // Two HTTP servers: one represents the *requester (remote) tutor* (the correct target),
    // the other the *local custodian tutor* (the legacy fallback that the bug used to hit).
    // The test asserts that with a session.remoteTutorBaseUrl set, the request lands on the
    // remote tutor and *not* on the local one.
    final HttpServer remoteTutor = HttpServer.create(new InetSocketAddress(0), 0);
    final HttpServer localTutor = HttpServer.create(new InetSocketAddress(0), 0);
    final AtomicInteger remoteHits = new AtomicInteger(0);
    final AtomicInteger localHits = new AtomicInteger(0);
    remoteTutor.createContext(
        "/recovery/fragments",
        exchange -> {
          remoteHits.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          final byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(201, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    localTutor.createContext(
        "/recovery/fragments",
        exchange -> {
          localHits.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          final byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(201, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    remoteTutor.start();
    localTutor.start();
    try {
      final InMemoryCustodyFragmentPort custodyPort = new InMemoryCustodyFragmentPort();
      final InMemoryCustodyFragmentPayloadPort payloadPort =
          new InMemoryCustodyFragmentPayloadPort();

      final String fragmentId = "frag-prefer-remote-1";
      final byte[] payload = "payload-prefer-remote".getBytes(StandardCharsets.UTF_8);
      final Instant now = Instant.parse("2026-04-27T10:00:00Z");
      custodyPort.save(
          new CustodyFragment(
              fragmentId,
              "agreement-prefer-remote-1",
              "node-remote",
              "SHA-256",
              "dummy-checksum",
              payload.length,
              now,
              now.plusSeconds(1200L)));
      payloadPort.save(fragmentId, payload);

      final NodeTopologyProperties topology = new NodeTopologyProperties();
      topology.setTutorBaseUrl("http://localhost:" + localTutor.getAddress().getPort());

      final CustodyLivenessProperties properties = new CustodyLivenessProperties();
      properties.setRequestTimeoutMillis(2000L);

      final TutorReturnCustodyEscalationPort escalationPort =
          new TutorReturnCustodyEscalationPort(
              topology,
              nodeIdentity("node-local"),
              custodyPort,
              payloadPort,
              properties,
              new CustodyFragmentLifecycleAdapter(custodyPort, payloadPort),
              CustodyLivenessObservabilityService.noop(),
              "SHA256withECDSA");

      final CustodyProbeSession session =
          new CustodyProbeSession(
              "session-prefer-remote-1",
              "node-remote",
              CustodyProbeDirection.INBOUND,
              es.ual.node.custodyliveness.domain.CustodyProbeStatus.UNRESPONSIVE,
              10,
              null,
              now,
              null,
              "timeout",
              null,
              now,
              now,
              "http://localhost:" + remoteTutor.getAddress().getPort());

      escalationPort.handleUnresponsive(
          session,
          List.of(
              new CustodyProbeFragment(
                  fragmentId, "agreement-prefer-remote-1", "dummy-checksum", payload.length)),
          "timeout",
          now,
          CustodyEscalationPolicy.RETURN_TO_TUTOR);

      assertEquals(1, remoteHits.get());
      assertEquals(0, localHits.get());
      assertFalse(custodyPort.findByFragmentId(fragmentId).isPresent());
    } finally {
      remoteTutor.stop(0);
      localTutor.stop(0);
    }
  }

  @Test
  void returnToTutorFallsBackToLocalTutorWhenSessionHasNoRemoteTutor() throws Exception {
    // Legacy session: remoteTutorBaseUrl is null. The escalation must use
    // the configured local tutor and emit a WARN log.
    final AtomicInteger localHits = new AtomicInteger(0);
    server.createContext(
        "/recovery/fragments",
        exchange -> {
          localHits.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          final byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(201, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();

    final InMemoryCustodyFragmentPort custodyPort = new InMemoryCustodyFragmentPort();
    final InMemoryCustodyFragmentPayloadPort payloadPort = new InMemoryCustodyFragmentPayloadPort();

    final String fragmentId = "frag-fallback-1";
    final byte[] payload = "payload-fallback".getBytes(StandardCharsets.UTF_8);
    final Instant now = Instant.parse("2026-04-27T10:00:00Z");
    custodyPort.save(
        new CustodyFragment(
            fragmentId,
            "agreement-fallback-1",
            "node-remote",
            "SHA-256",
            "dummy-checksum",
            payload.length,
            now,
            now.plusSeconds(1200L)));
    payloadPort.save(fragmentId, payload);

    final NodeTopologyProperties topology = new NodeTopologyProperties();
    topology.setTutorBaseUrl("http://localhost:" + server.getAddress().getPort());

    final CustodyLivenessProperties properties = new CustodyLivenessProperties();
    properties.setRequestTimeoutMillis(2000L);

    final TutorReturnCustodyEscalationPort escalationPort =
        new TutorReturnCustodyEscalationPort(
            topology,
            nodeIdentity("node-local"),
            custodyPort,
            payloadPort,
            properties,
            new CustodyFragmentLifecycleAdapter(custodyPort, payloadPort),
            CustodyLivenessObservabilityService.noop(),
            "SHA256withECDSA");

    final CustodyProbeSession session =
        CustodyProbeSession.withoutRemoteTutor(
            "session-fallback-1",
            "node-remote",
            CustodyProbeDirection.INBOUND,
            es.ual.node.custodyliveness.domain.CustodyProbeStatus.UNRESPONSIVE,
            10,
            null,
            now,
            null,
            "timeout",
            null,
            now,
            now);

    escalationPort.handleUnresponsive(
        session,
        List.of(
            new CustodyProbeFragment(
                fragmentId, "agreement-fallback-1", "dummy-checksum", payload.length)),
        "timeout",
        now,
        CustodyEscalationPolicy.RETURN_TO_TUTOR);

    assertEquals(1, localHits.get());
    assertFalse(custodyPort.findByFragmentId(fragmentId).isPresent());
  }

  @Test
  void returnToTutorPropagatesRequestCorrelationIdFromMdc() throws Exception {
    final AtomicReference<String> requestIdHeader = new AtomicReference<>();
    server.createContext(
        "/recovery/fragments",
        exchange -> {
          requestIdHeader.set(
              exchange
                  .getRequestHeaders()
                  .getFirst(RequestCorrelationInterceptor.HEADER_REQUEST_ID));

          exchange.getRequestBody().readAllBytes();
          final byte[] responseBody = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(201, responseBody.length);
          exchange.getResponseBody().write(responseBody);
          exchange.close();
        });
    server.start();

    final InMemoryCustodyFragmentPort custodyPort = new InMemoryCustodyFragmentPort();
    final InMemoryCustodyFragmentPayloadPort payloadPort = new InMemoryCustodyFragmentPayloadPort();

    final String fragmentId = "frag-return-mdc-1";
    final byte[] payload = "payload-to-return-mdc".getBytes(StandardCharsets.UTF_8);
    final Instant now = Instant.parse("2026-03-31T10:00:00Z");
    custodyPort.save(
        new CustodyFragment(
            fragmentId,
            "agreement-1",
            "node-remote",
            "SHA-256",
            "dummy-checksum",
            payload.length,
            now,
            now.plusSeconds(1200L)));
    payloadPort.save(fragmentId, payload);

    final NodeTopologyProperties topology = new NodeTopologyProperties();
    topology.setTutorBaseUrl("http://localhost:" + server.getAddress().getPort());

    final CustodyLivenessProperties properties = new CustodyLivenessProperties();
    properties.setRequestTimeoutMillis(2000L);

    final TutorReturnCustodyEscalationPort escalationPort =
        new TutorReturnCustodyEscalationPort(
            topology,
            nodeIdentity("node-local"),
            custodyPort,
            payloadPort,
            properties,
            new CustodyFragmentLifecycleAdapter(custodyPort, payloadPort),
            CustodyLivenessObservabilityService.noop(),
            "SHA256withECDSA");

    final CustodyProbeSession session =
        CustodyProbeSession.withoutRemoteTutor(
            "session-mdc-1",
            "node-remote",
            CustodyProbeDirection.OUTBOUND,
            es.ual.node.custodyliveness.domain.CustodyProbeStatus.UNRESPONSIVE,
            10,
            null,
            now,
            null,
            "timeout",
            null,
            now,
            now);

    MDC.put(RequestCorrelationInterceptor.MDC_REQUEST_ID_KEY, "req-mdc-escalation-123");
    escalationPort.handleUnresponsive(
        session,
        List.of(
            new CustodyProbeFragment(fragmentId, "agreement-1", "dummy-checksum", payload.length)),
        "timeout",
        now,
        CustodyEscalationPolicy.RETURN_TO_TUTOR);

    assertEquals("req-mdc-escalation-123", requestIdHeader.get());
  }

  // ----------------------------------------------------------------------------------
  // Escalation resilience: TTL renewal + custody preserved when tutor down
  // ----------------------------------------------------------------------------------

  @Test
  void escalationKeepsFragmentAndRenewsTtlWhenTutorRejectsWith403() throws Exception {
    server.createContext(
        "/recovery/fragments",
        exchange -> {
          exchange.sendResponseHeaders(403, -1);
          exchange.close();
        });
    server.start();

    final InMemoryCustodyFragmentPort custodyPort = new InMemoryCustodyFragmentPort();
    final InMemoryCustodyFragmentPayloadPort payloadPort = new InMemoryCustodyFragmentPayloadPort();
    final String fragmentId = "frag-403";
    final byte[] payload = "p".getBytes(StandardCharsets.UTF_8);
    final Instant now = Instant.parse("2026-03-31T10:00:00Z");
    final Instant originalExpiry = now.plusSeconds(60L);
    custodyPort.save(
        new CustodyFragment(
            fragmentId,
            "agr-1",
            "node-remote",
            "SHA-256",
            "x",
            payload.length,
            now,
            originalExpiry));
    payloadPort.save(fragmentId, payload);

    final NodeTopologyProperties topology = new NodeTopologyProperties();
    topology.setTutorBaseUrl("http://localhost:" + server.getAddress().getPort());
    final CustodyLivenessProperties properties = new CustodyLivenessProperties();
    properties.setRequestTimeoutMillis(2000L);
    properties.setEscalationTtlRenewalSeconds(7200L);
    final CustodyLivenessObservabilityService observability =
        new CustodyLivenessObservabilityService();

    final TutorReturnCustodyEscalationPort port =
        new TutorReturnCustodyEscalationPort(
            topology,
            nodeIdentity("node-local"),
            custodyPort,
            payloadPort,
            properties,
            new CustodyFragmentLifecycleAdapter(custodyPort, payloadPort),
            observability,
            "SHA256withECDSA");

    final CustodyProbeSession session =
        CustodyProbeSession.withoutRemoteTutor(
            "s-403",
            "node-remote",
            CustodyProbeDirection.OUTBOUND,
            es.ual.node.custodyliveness.domain.CustodyProbeStatus.UNRESPONSIVE,
            3,
            null,
            now,
            null,
            "timeout",
            null,
            now,
            now);

    final RuntimeException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () ->
                port.handleUnresponsive(
                    session,
                    List.of(new CustodyProbeFragment(fragmentId, "agr-1", "x", payload.length)),
                    "timeout",
                    now,
                    CustodyEscalationPolicy.RETURN_TO_TUTOR));
    org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("HTTP status 403"));

    // Fragment + payload SIGUEN presentes (no delete tras 403).
    org.junit.jupiter.api.Assertions.assertTrue(
        custodyPort.findByFragmentId(fragmentId).isPresent());
    org.junit.jupiter.api.Assertions.assertTrue(
        payloadPort.findByFragmentId(fragmentId).isPresent());

    // TTL renovado (expiresAt extendido más allá del original).
    final Instant newExpiry = custodyPort.findByFragmentId(fragmentId).get().expiresAt();
    org.junit.jupiter.api.Assertions.assertTrue(
        newExpiry.isAfter(originalExpiry),
        "expected TTL extended; original=" + originalExpiry + " new=" + newExpiry);

    // Counter incrementado.
    assertEquals(1L, observability.snapshot().escalationDeferredTotal());
  }

  @Test
  void escalationKeepsFragmentAndRenewsTtlWhenTutorReturnsTimeout() throws Exception {
    // Servidor que ACEPTA el handshake pero nunca responde — fuerza timeout.
    server.createContext(
        "/recovery/fragments",
        exchange -> {
          try {
            Thread.sleep(5000L);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    server.start();

    final InMemoryCustodyFragmentPort custodyPort = new InMemoryCustodyFragmentPort();
    final InMemoryCustodyFragmentPayloadPort payloadPort = new InMemoryCustodyFragmentPayloadPort();
    final String fragmentId = "frag-timeout";
    final byte[] payload = "p".getBytes(StandardCharsets.UTF_8);
    final Instant now = Instant.parse("2026-03-31T10:00:00Z");
    final Instant originalExpiry = now.plusSeconds(60L);
    custodyPort.save(
        new CustodyFragment(
            fragmentId,
            "agr-1",
            "node-remote",
            "SHA-256",
            "x",
            payload.length,
            now,
            originalExpiry));
    payloadPort.save(fragmentId, payload);

    final NodeTopologyProperties topology = new NodeTopologyProperties();
    topology.setTutorBaseUrl("http://localhost:" + server.getAddress().getPort());
    final CustodyLivenessProperties properties = new CustodyLivenessProperties();
    properties.setRequestTimeoutMillis(500L); // timeout corto para que el test tarde poco
    properties.setEscalationTtlRenewalSeconds(7200L);
    final CustodyLivenessObservabilityService observability =
        new CustodyLivenessObservabilityService();

    final TutorReturnCustodyEscalationPort port =
        new TutorReturnCustodyEscalationPort(
            topology,
            nodeIdentity("node-local"),
            custodyPort,
            payloadPort,
            properties,
            new CustodyFragmentLifecycleAdapter(custodyPort, payloadPort),
            observability,
            "SHA256withECDSA");

    final CustodyProbeSession session =
        CustodyProbeSession.withoutRemoteTutor(
            "s-to",
            "node-remote",
            CustodyProbeDirection.OUTBOUND,
            es.ual.node.custodyliveness.domain.CustodyProbeStatus.UNRESPONSIVE,
            3,
            null,
            now,
            null,
            "timeout",
            null,
            now,
            now);

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () ->
            port.handleUnresponsive(
                session,
                List.of(new CustodyProbeFragment(fragmentId, "agr-1", "x", payload.length)),
                "timeout",
                now,
                CustodyEscalationPolicy.RETURN_TO_TUTOR));

    org.junit.jupiter.api.Assertions.assertTrue(
        custodyPort.findByFragmentId(fragmentId).isPresent());
    org.junit.jupiter.api.Assertions.assertTrue(
        payloadPort.findByFragmentId(fragmentId).isPresent());
    final Instant newExpiry = custodyPort.findByFragmentId(fragmentId).get().expiresAt();
    org.junit.jupiter.api.Assertions.assertTrue(newExpiry.isAfter(originalExpiry));
    assertEquals(1L, observability.snapshot().escalationDeferredTotal());
  }

  @Test
  void escalationKeepsFragmentAndRenewsTtlWhenTutorRejectsWith500() throws Exception {
    server.createContext(
        "/recovery/fragments",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.start();

    final InMemoryCustodyFragmentPort custodyPort = new InMemoryCustodyFragmentPort();
    final InMemoryCustodyFragmentPayloadPort payloadPort = new InMemoryCustodyFragmentPayloadPort();
    final String fragmentId = "frag-500";
    final byte[] payload = "p".getBytes(StandardCharsets.UTF_8);
    final Instant now = Instant.parse("2026-03-31T10:00:00Z");
    final Instant originalExpiry = now.plusSeconds(60L);
    custodyPort.save(
        new CustodyFragment(
            fragmentId,
            "agr-1",
            "node-remote",
            "SHA-256",
            "x",
            payload.length,
            now,
            originalExpiry));
    payloadPort.save(fragmentId, payload);

    final NodeTopologyProperties topology = new NodeTopologyProperties();
    topology.setTutorBaseUrl("http://localhost:" + server.getAddress().getPort());
    final CustodyLivenessProperties properties = new CustodyLivenessProperties();
    properties.setRequestTimeoutMillis(2000L);
    properties.setEscalationTtlRenewalSeconds(7200L);
    final CustodyLivenessObservabilityService observability =
        new CustodyLivenessObservabilityService();

    final TutorReturnCustodyEscalationPort port =
        new TutorReturnCustodyEscalationPort(
            topology,
            nodeIdentity("node-local"),
            custodyPort,
            payloadPort,
            properties,
            new CustodyFragmentLifecycleAdapter(custodyPort, payloadPort),
            observability,
            "SHA256withECDSA");

    final CustodyProbeSession session =
        CustodyProbeSession.withoutRemoteTutor(
            "s-500",
            "node-remote",
            CustodyProbeDirection.OUTBOUND,
            es.ual.node.custodyliveness.domain.CustodyProbeStatus.UNRESPONSIVE,
            3,
            null,
            now,
            null,
            "timeout",
            null,
            now,
            now);

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () ->
            port.handleUnresponsive(
                session,
                List.of(new CustodyProbeFragment(fragmentId, "agr-1", "x", payload.length)),
                "timeout",
                now,
                CustodyEscalationPolicy.RETURN_TO_TUTOR));

    org.junit.jupiter.api.Assertions.assertTrue(
        custodyPort.findByFragmentId(fragmentId).isPresent());
    final Instant newExpiry = custodyPort.findByFragmentId(fragmentId).get().expiresAt();
    org.junit.jupiter.api.Assertions.assertTrue(newExpiry.isAfter(originalExpiry));
    assertEquals(1L, observability.snapshot().escalationDeferredTotal());
  }

  private static NodeIdentityContext nodeIdentity(final String nodeId) throws Exception {
    final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
    keyPairGenerator.initialize(256);
    final KeyPair keyPair = keyPairGenerator.generateKeyPair();
    return new NodeIdentityContext(nodeId, keyPair.getPublic(), keyPair.getPrivate());
  }
}
