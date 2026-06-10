package es.ual.node.custodyliveness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentInventoryPort;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentLifecyclePort;
import es.ual.node.custodyliveness.ports.out.CustodyProbeSessionPort;
import es.ual.node.custodyliveness.ports.out.RemoteOriginKeepListClientPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests del servicio custodian-side que inicia el probe inverso de keep-list al origen.
 *
 * <p>Whitelist puro (custodian purga lo no mencionado) + manejo de error de red (no purga si origen
 * no responde).
 */
class CustodianOutboundKeepListServiceTest {

  private static final String ORIGIN_ID = "node-origin-aaa";
  private static final String ORIGIN_URL = "http://node-origin:8080";
  private static final String OTHER_ORIGIN_ID = "node-origin-zzz";
  private static final Instant NOW = Instant.parse("2026-05-04T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String CHECKSUM =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  private static final String TUTOR_URL = "http://node-tutor:8080";

  private final RecordingInventoryPort inventoryPort = new RecordingInventoryPort();
  private final RecordingLifecyclePort lifecyclePort = new RecordingLifecyclePort();
  private final RecordingClient client = new RecordingClient();
  private final CustodyLivenessProperties properties = new CustodyLivenessProperties();
  private final InMemorySessionPort sessionPort = new InMemorySessionPort();
  private CustodianOutboundKeepListService service;

  private static String hintId(final String requesterNodeId) {
    return CustodyProbeSession.ORIGIN_TUTOR_HINT_PREFIX + requesterNodeId;
  }

  @BeforeEach
  void setUp() {
    properties.getRemoteBaseUrls().put(ORIGIN_ID, ORIGIN_URL);
    service =
        new CustodianOutboundKeepListService(
            inventoryPort, lifecyclePort, client, properties, sessionPort, CLOCK);
  }

  @Test
  void learnsOriginTutorHintFromKeepListResponse() {
    inventoryPort.setRequesters(List.of(ORIGIN_ID));
    inventoryPort.setFragmentsForRequester(ORIGIN_ID, List.of(probeFragment("frag-A")));
    client.respondWith(List.of("frag-A"));
    client.advertiseTutor(TUTOR_URL);

    service.runOnce();

    final CustodyProbeSession hint = sessionPort.findById(hintId(ORIGIN_ID)).orElseThrow();
    assertEquals(TUTOR_URL, hint.remoteTutorBaseUrl());
    assertEquals(ORIGIN_ID, hint.remoteNodeId());
    assertNull(hint.nextAttemptAt(), "hint must be inert (never due)");
  }

  @Test
  void clearsOriginTutorHintWhenOriginReturnsEmptyKeepList() {
    // primero aprende el tutor
    inventoryPort.setRequesters(List.of(ORIGIN_ID));
    inventoryPort.setFragmentsForRequester(ORIGIN_ID, List.of(probeFragment("frag-A")));
    client.respondWith(List.of("frag-A"));
    client.advertiseTutor(TUTOR_URL);
    service.runOnce();
    assertEquals(
        TUTOR_URL, sessionPort.findById(hintId(ORIGIN_ID)).orElseThrow().remoteTutorBaseUrl());

    // el origen ya no quiere conservar nada → keep-list vacío → purga del hint
    client.respondWith(List.of());
    service.runOnce();

    assertNull(sessionPort.findById(hintId(ORIGIN_ID)).orElseThrow().remoteTutorBaseUrl());
  }

  @Test
  void purgesFragmentsNotInKeepListReturnedByOrigin() {
    inventoryPort.setRequesters(List.of(ORIGIN_ID));
    inventoryPort.setFragmentsForRequester(
        ORIGIN_ID,
        List.of(probeFragment("frag-A"), probeFragment("frag-B"), probeFragment("frag-C")));
    client.respondWith(List.of("frag-A", "frag-B")); // origen quiere conservar A y B

    final CustodianOutboundKeepListService.CycleSummary summary = service.runOnce();

    assertEquals(1, summary.probesSent());
    assertEquals(1, summary.totalPurged());
    assertEquals(0, summary.requesterErrors());
    assertEquals(Set.of("frag-C"), lifecyclePort.decommissioned);
  }

  @Test
  void doesNotPurgeIfKeepListContainsAllFragments() {
    inventoryPort.setRequesters(List.of(ORIGIN_ID));
    inventoryPort.setFragmentsForRequester(ORIGIN_ID, List.of(probeFragment("frag-A")));
    client.respondWith(List.of("frag-A"));

    final CustodianOutboundKeepListService.CycleSummary summary = service.runOnce();

    assertEquals(1, summary.probesSent());
    assertEquals(0, summary.totalPurged());
    assertTrue(lifecyclePort.decommissioned.isEmpty());
  }

  @Test
  void doesNotPurgeWhenOriginDoesNotRespond() {
    inventoryPort.setRequesters(List.of(ORIGIN_ID));
    inventoryPort.setFragmentsForRequester(
        ORIGIN_ID, List.of(probeFragment("frag-A"), probeFragment("frag-B")));
    client.failWith("network unreachable");

    final CustodianOutboundKeepListService.CycleSummary summary = service.runOnce();

    assertEquals(0, summary.probesSent());
    assertEquals(0, summary.totalPurged());
    assertEquals(1, summary.requesterErrors());
    assertTrue(lifecyclePort.decommissioned.isEmpty(), "no purge if origin silent");
  }

  @Test
  void skipsRequesterWithoutBaseUrl() {
    inventoryPort.setRequesters(List.of(OTHER_ORIGIN_ID));
    inventoryPort.setFragmentsForRequester(OTHER_ORIGIN_ID, List.of(probeFragment("frag-X")));

    final CustodianOutboundKeepListService.CycleSummary summary = service.runOnce();

    assertEquals(0, summary.probesSent());
    assertEquals(1, summary.requesterErrors());
    assertEquals(0, client.callCount);
    assertTrue(lifecyclePort.decommissioned.isEmpty());
  }

  @Test
  void emptyInventorySkipsProbeForRequester() {
    inventoryPort.setRequesters(List.of(ORIGIN_ID));
    inventoryPort.setFragmentsForRequester(ORIGIN_ID, List.of());

    final CustodianOutboundKeepListService.CycleSummary summary = service.runOnce();

    assertEquals(0, summary.probesSent());
    assertEquals(0, client.callCount);
  }

  // ---------- Helpers ----------

  private CustodyProbeFragment probeFragment(final String fragmentId) {
    return new CustodyProbeFragment(fragmentId, "agreement-" + fragmentId, CHECKSUM, 1024L);
  }

  private static final class RecordingInventoryPort implements CustodyFragmentInventoryPort {
    private List<String> requesters = List.of();
    private final java.util.Map<String, List<CustodyProbeFragment>> byRequester =
        new java.util.HashMap<>();

    void setRequesters(final List<String> requesters) {
      this.requesters = requesters;
    }

    void setFragmentsForRequester(final String requester, final List<CustodyProbeFragment> frags) {
      byRequester.put(requester, frags);
    }

    @Override
    public List<CustodyProbeFragment> findCustodiedForRequester(
        final String requesterNodeId, final Instant now) {
      return byRequester.getOrDefault(requesterNodeId, List.of());
    }

    @Override
    public List<String> listDistinctRequesterNodeIds() {
      return requesters;
    }
  }

  private static final class RecordingLifecyclePort implements CustodyFragmentLifecyclePort {
    final Set<String> decommissioned = new HashSet<>();

    @Override
    public java.util.Optional<es.ual.node.fragmentstorage.domain.CustodyFragment> findByFragmentId(
        final String fragmentId) {
      return java.util.Optional.empty();
    }

    @Override
    public void extendCustody(final String fragmentId, final long additionalSeconds) {
      // not used in these tests
    }

    @Override
    public void releaseCustody(final String fragmentId) {
      // not used in these tests
    }

    @Override
    public void decommissionCustody(final String fragmentId) {
      decommissioned.add(fragmentId);
    }
  }

  private static final class RecordingClient implements RemoteOriginKeepListClientPort {
    int callCount = 0;
    private List<String> response = new ArrayList<>();
    private String tutorBaseUrl;
    private String failureMessage;

    void respondWith(final List<String> keepFragmentIds) {
      this.response = new ArrayList<>(keepFragmentIds);
      this.failureMessage = null;
    }

    void advertiseTutor(final String tutorBaseUrl) {
      this.tutorBaseUrl = tutorBaseUrl;
    }

    void failWith(final String message) {
      this.failureMessage = message;
    }

    @Override
    public OriginKeepListResult requestKeepList(
        final String originBaseUrl, final String requesterNodeId, final List<String> fragmentIds) {
      callCount++;
      if (failureMessage != null) {
        throw new RemoteOriginKeepListException(failureMessage);
      }
      return new OriginKeepListResult(List.copyOf(response), tutorBaseUrl);
    }
  }

  private static final class InMemorySessionPort implements CustodyProbeSessionPort {
    private final Map<String, CustodyProbeSession> saved = new HashMap<>();

    @Override
    public void save(final CustodyProbeSession session) {
      saved.put(session.sessionId(), session);
    }

    @Override
    public Optional<CustodyProbeSession> findById(final String sessionId) {
      return Optional.ofNullable(saved.get(sessionId));
    }

    @Override
    public List<CustodyProbeSession> findByRemoteNodeId(final String remoteNodeId) {
      return saved.values().stream().filter(s -> remoteNodeId.equals(s.remoteNodeId())).toList();
    }

    @Override
    public List<CustodyProbeSession> findAll() {
      return List.copyOf(saved.values());
    }

    @Override
    public List<CustodyProbeSession> findDueOutbound(final Instant now, final int limit) {
      return List.of();
    }
  }
}
