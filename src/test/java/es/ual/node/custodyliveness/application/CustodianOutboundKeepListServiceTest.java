package es.ual.node.custodyliveness.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentInventoryPort;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentLifecyclePort;
import es.ual.node.custodyliveness.ports.out.RemoteOriginKeepListClientPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

  private final RecordingInventoryPort inventoryPort = new RecordingInventoryPort();
  private final RecordingLifecyclePort lifecyclePort = new RecordingLifecyclePort();
  private final RecordingClient client = new RecordingClient();
  private final CustodyLivenessProperties properties = new CustodyLivenessProperties();
  private CustodianOutboundKeepListService service;

  @BeforeEach
  void setUp() {
    properties.getRemoteBaseUrls().put(ORIGIN_ID, ORIGIN_URL);
    service =
        new CustodianOutboundKeepListService(
            inventoryPort, lifecyclePort, client, properties, CLOCK);
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
    private String failureMessage;

    void respondWith(final List<String> keepFragmentIds) {
      this.response = new ArrayList<>(keepFragmentIds);
      this.failureMessage = null;
    }

    void failWith(final String message) {
      this.failureMessage = message;
    }

    @Override
    public List<String> requestKeepList(
        final String originBaseUrl, final String requesterNodeId, final List<String> fragmentIds) {
      callCount++;
      if (failureMessage != null) {
        throw new RemoteOriginKeepListException(failureMessage);
      }
      return List.copyOf(response);
    }
  }
}
