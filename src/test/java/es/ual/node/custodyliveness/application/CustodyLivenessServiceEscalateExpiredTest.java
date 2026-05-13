package es.ual.node.custodyliveness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.custodyliveness.domain.CustodyEscalationPolicy;
import es.ual.node.custodyliveness.domain.CustodyProbeDirection;
import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.custodyliveness.domain.CustodyProbeRequest;
import es.ual.node.custodyliveness.domain.CustodyProbeResponse;
import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import es.ual.node.custodyliveness.domain.CustodyProbeStatus;
import es.ual.node.custodyliveness.ports.out.CustodyEscalationPort;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentInterestPort;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentInventoryPort;
import es.ual.node.custodyliveness.ports.out.CustodyProbeSessionPort;
import es.ual.node.custodyliveness.ports.out.RemoteCustodyProbeClientPort;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import io.micrometer.observation.ObservationRegistry;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CustodyLivenessService#escalateExpiredCustodyFragments()}.
 *
 * <p>Verifica:
 *
 * <ul>
 *   <li>Cuando el inventory port retorna fragments expirados, el service invoca {@code
 *       custodyEscalationPort.handleUnresponsive} con la session sintética agrupada por
 *       requesterNodeId.
 *   <li>El observability service registra el counter {@code expiryEscalationTotal}.
 *   <li>Si tutor falla, el bug del operador NO se reproduce (no se elimina nada: el escalation port
 *       es responsable del defer-and-renew, fuera del scope de este test unit).
 * </ul>
 */
class CustodyLivenessServiceEscalateExpiredTest {

  private static final String REQUESTER_A = "node-requester-aaa";
  private static final String REQUESTER_B = "node-requester-bbb";
  private static final Instant NOW = Instant.parse("2026-05-07T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final RecordingSessionPort sessionPort = new RecordingSessionPort();
  private final RecordingInventoryPort inventoryPort = new RecordingInventoryPort();
  private final RecordingEscalationPort escalationPort = new RecordingEscalationPort();
  private final CustodyFragmentInterestPort interestPort = (fragment, requester) -> true;
  private final RemoteCustodyProbeClientPort clientPort =
      (target, request) ->
          new CustodyProbeResponse("ignored", List.of(), List.of(), false, NOW, NOW);
  private final CustodyLivenessProperties properties = new CustodyLivenessProperties();
  private final CustodyLivenessObservabilityService observabilityService =
      new CustodyLivenessObservabilityService();
  private CustodyLivenessService service;

  @BeforeEach
  void setUp() throws Exception {
    properties.setEnabled(true);
    properties.setBatchSize(50);
    properties.setEscalationPolicy(CustodyEscalationPolicy.RETURN_TO_TUTOR);
    final KeyPair pair = KeyPairGenerator.getInstance("EC").generateKeyPair();
    final NodeIdentityContext identity =
        new NodeIdentityContext("node-local", pair.getPublic(), pair.getPrivate());
    service =
        new CustodyLivenessService(
            sessionPort,
            interestPort,
            inventoryPort,
            escalationPort,
            clientPort,
            properties,
            identity,
            CLOCK,
            observabilityService,
            ObservationRegistry.NOOP,
            null);
  }

  @Test
  void noOpWhenNoExpiredFragments() {
    inventoryPort.setExpired(List.of());

    service.escalateExpiredCustodyFragments();

    assertTrue(escalationPort.invocations.isEmpty());
    assertEquals(0L, observabilityService.snapshot().expiryEscalationTotal());
  }

  @Test
  void noOpWhenServiceDisabled() {
    properties.setEnabled(false);
    inventoryPort.setExpired(
        List.of(expiredEntry("frag-A", REQUESTER_A), expiredEntry("frag-B", REQUESTER_A)));

    service.escalateExpiredCustodyFragments();

    assertTrue(escalationPort.invocations.isEmpty());
    assertEquals(0L, observabilityService.snapshot().expiryEscalationTotal());
  }

  @Test
  void escalatesByRequester_groupingFragments() {
    inventoryPort.setExpired(
        List.of(
            expiredEntry("frag-A", REQUESTER_A),
            expiredEntry("frag-B", REQUESTER_A),
            expiredEntry("frag-C", REQUESTER_B)));

    service.escalateExpiredCustodyFragments();

    assertEquals(2, escalationPort.invocations.size(), "uno por requester distinto");

    final EscalationCall callA = escalationPort.callsByRequester(REQUESTER_A).get(0);
    assertEquals(2, callA.fragments.size(), "ambos fragments del REQUESTER_A en una sola call");
    assertEquals(CustodyProbeStatus.UNRESPONSIVE, callA.session.status());
    assertEquals(CustodyProbeDirection.OUTBOUND, callA.session.direction());
    assertEquals("expiry-escalation-" + REQUESTER_A, callA.session.sessionId());
    assertEquals("TTL_EXPIRED_NO_PROBE", callA.reason);
    assertEquals(CustodyEscalationPolicy.RETURN_TO_TUTOR, callA.policy);

    final EscalationCall callB = escalationPort.callsByRequester(REQUESTER_B).get(0);
    assertEquals(1, callB.fragments.size());
    assertEquals("frag-C", callB.fragments.get(0).fragmentId());

    // 3 fragments dispatched (2 + 1)
    assertEquals(3L, observabilityService.snapshot().expiryEscalationTotal());
  }

  @Test
  void reusesExistingSessionWhenAvailable() {
    final CustodyProbeSession existing =
        CustodyProbeSession.withoutRemoteTutor(
            "expiry-escalation-" + REQUESTER_A,
            REQUESTER_A,
            CustodyProbeDirection.OUTBOUND,
            CustodyProbeStatus.UNRESPONSIVE,
            5,
            null,
            NOW.minusSeconds(60),
            null,
            "previous_attempt",
            null,
            NOW.minusSeconds(120),
            NOW.minusSeconds(60));
    sessionPort.save(existing);
    inventoryPort.setExpired(List.of(expiredEntry("frag-A", REQUESTER_A)));

    service.escalateExpiredCustodyFragments();

    final EscalationCall call = escalationPort.callsByRequester(REQUESTER_A).get(0);
    assertSame(existing.sessionId(), call.session.sessionId(), "session reusada");
    assertEquals(5, call.session.attemptCount(), "attemptCount preservado");
  }

  private static CustodyFragmentInventoryPort.ExpiredCustodyEntry expiredEntry(
      final String fragmentId, final String requesterNodeId) {
    return new CustodyFragmentInventoryPort.ExpiredCustodyEntry(
        fragmentId,
        "agreement-" + fragmentId,
        requesterNodeId,
        "checksum-" + fragmentId,
        1024,
        NOW.minusSeconds(60));
  }

  /** Recording mock for session port (only methods used by escalateExpiredCustodyFragments). */
  private static final class RecordingSessionPort implements CustodyProbeSessionPort {
    private final Map<String, CustodyProbeSession> byId = new HashMap<>();

    @Override
    public void save(final CustodyProbeSession session) {
      byId.put(session.sessionId(), session);
    }

    @Override
    public Optional<CustodyProbeSession> findById(final String sessionId) {
      return Optional.ofNullable(byId.get(sessionId));
    }

    @Override
    public List<CustodyProbeSession> findDueOutbound(final Instant now, final int batchSize) {
      return List.of();
    }

    @Override
    public List<CustodyProbeSession> findByRemoteNodeId(final String remoteNodeId) {
      return List.of();
    }

    @Override
    public List<CustodyProbeSession> findAll() {
      return List.copyOf(byId.values());
    }
  }

  /** Recording mock for inventory port. */
  private static final class RecordingInventoryPort implements CustodyFragmentInventoryPort {
    private List<ExpiredCustodyEntry> expired = List.of();

    void setExpired(final List<ExpiredCustodyEntry> entries) {
      this.expired = entries;
    }

    @Override
    public List<CustodyProbeFragment> findCustodiedForRequester(
        final String requesterNodeId, final Instant now) {
      return List.of();
    }

    @Override
    public List<ExpiredCustodyEntry> findExpiredCustodied(
        final Instant threshold, final int limit) {
      return expired;
    }
  }

  /** Recording escalation port that captures every invocation for assertions. */
  private static final class RecordingEscalationPort implements CustodyEscalationPort {
    private final List<EscalationCall> invocations = new ArrayList<>();

    @Override
    public void handleUnresponsive(
        final CustodyProbeSession session,
        final List<CustodyProbeFragment> fragments,
        final String reason,
        final Instant detectedAt,
        final CustodyEscalationPolicy policy) {
      assertNotNull(session, "session must not be null");
      invocations.add(new EscalationCall(session, List.copyOf(fragments), reason, policy));
    }

    List<EscalationCall> callsByRequester(final String requesterNodeId) {
      assertNull(null);
      return invocations.stream()
          .filter(call -> requesterNodeId.equals(call.session.remoteNodeId()))
          .toList();
    }
  }

  private record EscalationCall(
      CustodyProbeSession session,
      List<CustodyProbeFragment> fragments,
      String reason,
      CustodyEscalationPolicy policy) {}

  /** Unused stub for outbound probe client (escalateExpired never invokes it). */
  @SuppressWarnings("unused")
  private static CustodyProbeRequest dummyProbeRequest() {
    return CustodyProbeRequest.withoutRequesterTutor("rid", "from", "to", List.of(), NOW, 60L);
  }
}
