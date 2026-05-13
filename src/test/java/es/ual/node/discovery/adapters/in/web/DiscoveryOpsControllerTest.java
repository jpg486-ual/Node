package es.ual.node.discovery.adapters.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ual.node.discovery.application.DiscoveryCandidateDirectoryService;
import es.ual.node.discovery.application.DiscoveryMetricsSnapshot;
import es.ual.node.discovery.application.DiscoveryObservabilityService;
import es.ual.node.discovery.application.DiscoveryRetryQueueService;
import es.ual.node.discovery.application.DiscoveryService;
import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import es.ual.node.discovery.domain.DiscoveryRequest;
import es.ual.node.discovery.domain.DiscoveryResponse;
import es.ual.node.discovery.domain.DiscoveryRetryRequest;
import es.ual.node.discovery.domain.DiscoveryRetryStatus;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/** Unit tests for {@link DiscoveryOpsController}. */
@ExtendWith(MockitoExtension.class)
class DiscoveryOpsControllerTest {

  @Mock private DiscoveryRetryQueueService discoveryRetryQueueService;

  @Mock private DiscoveryCandidateDirectoryService discoveryCandidateDirectoryService;

  @Mock private DiscoveryObservabilityService discoveryObservabilityService;

  @Mock private DiscoveryService discoveryService;

  private DiscoveryOpsController controller;

  @BeforeEach
  void setUp() {
    controller =
        new DiscoveryOpsController(
            discoveryRetryQueueService,
            discoveryCandidateDirectoryService,
            discoveryObservabilityService,
            discoveryService);
  }

  @Test
  void getQueuedStatusReturnsOkWhenRequestExists() {
    final DiscoveryRetryRequest retryRequest =
        sampleRetry("queued-1", DiscoveryRetryStatus.PENDING, null);
    when(discoveryRetryQueueService.get("queued-1")).thenReturn(retryRequest);

    final ResponseEntity<DiscoveryRetryStatusPayload> response =
        controller.getQueuedStatus("queued-1");

    assertEquals(200, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals("queued-1", response.getBody().id());
    assertEquals(DiscoveryRetryStatus.PENDING, response.getBody().status());
  }

  @Test
  void getQueuedStatusReturnsNotFoundWhenRequestDoesNotExist() {
    when(discoveryRetryQueueService.get("missing-id"))
        .thenThrow(new NoSuchElementException("missing"));

    final ResponseEntity<DiscoveryRetryStatusPayload> response =
        controller.getQueuedStatus("missing-id");

    assertEquals(404, response.getStatusCode().value());
  }

  @Test
  void cancelQueuedReturnsOkWithUpdatedStatus() {
    final DiscoveryRetryRequest retryRequest =
        sampleRetry("queued-2", DiscoveryRetryStatus.FAILED, "ops cancel");
    when(discoveryRetryQueueService.cancel(eq("queued-2"), eq("ops cancel")))
        .thenReturn(retryRequest);

    final DiscoveryRetryCancelPayload payload = new DiscoveryRetryCancelPayload();
    payload.setReason("ops cancel");

    final ResponseEntity<DiscoveryRetryStatusPayload> response =
        controller.cancelQueued("queued-2", payload);

    assertEquals(200, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals(DiscoveryRetryStatus.FAILED, response.getBody().status());
    assertEquals("ops cancel", response.getBody().lastError());
  }

  @Test
  void getCandidatesReturnsMappedPayloadList() {
    when(discoveryCandidateDirectoryService.findActiveCandidates())
        .thenReturn(
            List.of(
                new DiscoveryCandidateProfile(
                    "node-a", "zone-a/rack-1", "http://node-a:8080", 1024L, Set.of(1024L)),
                new DiscoveryCandidateProfile(
                    "node-b", "zone-a/rack-2", "http://node-b:8080", 2048L, Set.of())));

    final ResponseEntity<List<DiscoveryCandidatePayload>> response = controller.getCandidates();

    assertEquals(200, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals(2, response.getBody().size());
    assertEquals("node-a", response.getBody().getFirst().nodeId());
  }

  @Test
  void metricsReturnsExpectedKeys() {
    when(discoveryObservabilityService.snapshot())
        .thenReturn(new DiscoveryMetricsSnapshot(3L, 5L, 1L, 4L));

    final ResponseEntity<DiscoveryMetricsPayload> response = controller.metrics();

    assertEquals(200, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertTrue(response.getBody().metrics().containsKey("discovery.queue.pending.count"));
    assertTrue(response.getBody().metrics().containsKey("discovery.queue.resolved.count"));
    assertTrue(response.getBody().metrics().containsKey("discovery.queue.failed.count"));
    assertTrue(response.getBody().metrics().containsKey("discovery.queue.total.count"));
    assertTrue(response.getBody().metrics().containsKey("discovery.candidates.active.count"));
  }

  @Test
  void upsertCandidateReturnsOkAndDelegatesToService() {
    final DiscoveryCandidateUpsertPayload payload = new DiscoveryCandidateUpsertPayload();
    payload.setFailureDomain("zone-a/rack-1");
    payload.setBaseUrl("http://node-a:8080");
    payload.setOriginalRequestedBucket(1024L);
    payload.setAcceptedBuckets(Set.of(1024L, 2048L));

    final ResponseEntity<DiscoveryCandidatePayload> response =
        controller.upsertCandidate("node-a", payload);

    assertEquals(200, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals("node-a", response.getBody().nodeId());

    final ArgumentCaptor<DiscoveryCandidateProfile> profileCaptor =
        ArgumentCaptor.forClass(DiscoveryCandidateProfile.class);
    verify(discoveryCandidateDirectoryService).upsertCandidate(profileCaptor.capture());
    assertEquals("node-a", profileCaptor.getValue().nodeId());
    assertEquals("zone-a/rack-1", profileCaptor.getValue().failureDomain());
    assertEquals("http://node-a:8080", profileCaptor.getValue().baseUrl());
    assertTrue(profileCaptor.getValue().acceptedBuckets().contains(2048L));
  }

  @Test
  void upsertCandidateReturnsBadRequestWhenPayloadIsInvalid() {
    final DiscoveryCandidateUpsertPayload payload = new DiscoveryCandidateUpsertPayload();
    payload.setFailureDomain("zone-a/rack-1");
    payload.setOriginalRequestedBucket(0L);
    payload.setAcceptedBuckets(Set.of());

    final ResponseEntity<DiscoveryCandidatePayload> response =
        controller.upsertCandidate("node-a", payload);

    assertEquals(400, response.getStatusCode().value());
  }

  @Test
  void deleteCandidateReturnsNoContentWhenDelegationSucceeds() {
    final ResponseEntity<Void> response = controller.deleteCandidate("node-a");

    assertEquals(204, response.getStatusCode().value());
    verify(discoveryCandidateDirectoryService).removeCandidate("node-a");
  }

  @Test
  void deleteCandidateReturnsBadRequestWhenServiceRejectsInput() {
    doThrow(new IllegalArgumentException("invalid"))
        .when(discoveryCandidateDirectoryService)
        .removeCandidate(any());

    final ResponseEntity<Void> response = controller.deleteCandidate(" ");

    assertEquals(400, response.getStatusCode().value());
  }

  private static DiscoveryRetryRequest sampleRetry(
      final String id, final DiscoveryRetryStatus status, final String lastError) {
    final Instant now = Instant.parse("2026-04-17T20:00:00Z");
    return new DiscoveryRetryRequest(
        id,
        new DiscoveryRequest("requester", "zone-a", 1024L, 1.0d, 10),
        status,
        1,
        now,
        now,
        now,
        status == DiscoveryRetryStatus.RESOLVED || status == DiscoveryRetryStatus.FAILED
            ? now
            : null,
        status == DiscoveryRetryStatus.RESOLVED ? 1 : null,
        lastError);
  }

  // ---------- POST /ops/discovery/query ----------

  @Test
  void queryReturnsCandidatesWhenDirectoryHasMatches() {
    final DiscoveryResponse domainResponse =
        new DiscoveryResponse(
            List.of(
                new DiscoveryResponse.CandidateNode("node-aaa", "http://node-aaa:8080", 1024L),
                new DiscoveryResponse.CandidateNode("node-bbb", "http://node-bbb:8080", 2048L)));
    when(discoveryService.discover(any(DiscoveryRequest.class))).thenReturn(domainResponse);

    final DiscoveryRequestPayload payload = new DiscoveryRequestPayload();
    payload.setNodeId("requester-1");
    payload.setFailureDomain("zone-a/rack-1");
    payload.setRequestedBucket(1024L);
    payload.setRatio(1.0);
    payload.setMaxCandidates(5);

    final ResponseEntity<DiscoveryResponsePayload> response = controller.query(payload);

    assertEquals(200, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals(2, response.getBody().candidates().size());
    assertEquals("node-aaa", response.getBody().candidates().get(0).nodeId());
    assertEquals("http://node-aaa:8080", response.getBody().candidates().get(0).baseUrl());
    assertEquals(1024L, response.getBody().candidates().get(0).originalBucketSize());
  }

  @Test
  void queryEnqueuesRetryWhenDirectoryEmpty() {
    when(discoveryService.discover(any(DiscoveryRequest.class)))
        .thenReturn(new DiscoveryResponse(List.of()));
    final DiscoveryRetryRequest queued =
        sampleRetry("queued-empty", DiscoveryRetryStatus.PENDING, null);
    when(discoveryRetryQueueService.enqueue(any(DiscoveryRequest.class))).thenReturn(queued);

    final DiscoveryRequestPayload payload = new DiscoveryRequestPayload();
    payload.setNodeId("requester-1");
    payload.setFailureDomain("zone-a/rack-1");
    payload.setRequestedBucket(1024L);
    payload.setRatio(1.0);
    payload.setMaxCandidates(5);

    final ResponseEntity<DiscoveryResponsePayload> response = controller.query(payload);

    assertEquals(200, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertTrue(response.getBody().candidates().isEmpty());
    assertEquals("queued-empty", response.getBody().queuedRequestId());
    verify(discoveryRetryQueueService).enqueue(any(DiscoveryRequest.class));
  }

  @Test
  void queryPropagatesNullDistributionPlanAsEmptyMap() {
    final ArgumentCaptor<DiscoveryRequest> captor = ArgumentCaptor.forClass(DiscoveryRequest.class);
    when(discoveryService.discover(captor.capture()))
        .thenReturn(
            new DiscoveryResponse(
                List.of(
                    new DiscoveryResponse.CandidateNode(
                        "node-aaa", "http://node-aaa:8080", 1024L))));

    final DiscoveryRequestPayload payload = new DiscoveryRequestPayload();
    payload.setNodeId("requester-1");
    payload.setFailureDomain("zone-a/rack-1");
    payload.setRequestedBucket(1024L);
    payload.setRatio(1.0);
    payload.setMaxCandidates(5);
    payload.setDistributionPlan(null);

    controller.query(payload);

    assertNotNull(captor.getValue().distributionPlan());
    assertTrue(captor.getValue().distributionPlan().isEmpty());
  }
}
