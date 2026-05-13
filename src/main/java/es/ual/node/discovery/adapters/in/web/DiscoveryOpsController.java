package es.ual.node.discovery.adapters.in.web;

import es.ual.node.discovery.application.DiscoveryCandidateDirectoryService;
import es.ual.node.discovery.application.DiscoveryObservabilityService;
import es.ual.node.discovery.application.DiscoveryRetryQueueService;
import es.ual.node.discovery.application.DiscoveryService;
import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import es.ual.node.discovery.domain.DiscoveryRequest;
import es.ual.node.discovery.domain.DiscoveryResponse;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations endpoints for discovery supernode.
 *
 * <p>Activos sólo cuando {@code node.discovery.supernode-role-enabled=true}: el controller requiere
 * los beans del lado server (directorio + retry queue + service) que sólo existen en supernodos.
 * Endpoints expuestos:
 *
 * <ul>
 *   <li>{@code GET /ops/discovery/queued/{id}} — retry status visibility.
 *   <li>{@code POST /ops/discovery/queued/{id}/cancel} — cancel queued retry.
 *   <li>{@code GET /ops/discovery/candidates} — active directory listing.
 *   <li>{@code GET /ops/discovery/metrics} — aggregated metrics.
 *   <li>{@code PUT /ops/discovery/candidates/{nodeId}} — registration upsert.
 *   <li>{@code DELETE /ops/discovery/candidates/{nodeId}} — registration removal.
 *   <li>{@code POST /ops/discovery/query} — inter-node candidate query
 * </ul>
 *
 * <p>Validación signed automática vía {@code RequestSignatureValidator} (el path {@code /ops/**}
 * cae bajo el interceptor inter-node por defecto).
 */
@RestController
@ConditionalOnProperty(
    prefix = "node.discovery",
    name = "supernode-role-enabled",
    havingValue = "true")
@RequestMapping("/ops/discovery")
public class DiscoveryOpsController {

  private final DiscoveryRetryQueueService discoveryRetryQueueService;
  private final DiscoveryCandidateDirectoryService discoveryCandidateDirectoryService;
  private final DiscoveryObservabilityService discoveryObservabilityService;
  private final DiscoveryService discoveryService;

  /**
   * Creates operations controller.
   *
   * @param discoveryRetryQueueService retry queue service
   * @param discoveryCandidateDirectoryService candidate directory service
   * @param discoveryObservabilityService observability service
   * @param discoveryService discovery query service (sirve {@code POST /query})
   */
  public DiscoveryOpsController(
      final DiscoveryRetryQueueService discoveryRetryQueueService,
      final DiscoveryCandidateDirectoryService discoveryCandidateDirectoryService,
      final DiscoveryObservabilityService discoveryObservabilityService,
      final DiscoveryService discoveryService) {
    if (discoveryRetryQueueService == null
        || discoveryCandidateDirectoryService == null
        || discoveryObservabilityService == null
        || discoveryService == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.discoveryRetryQueueService = discoveryRetryQueueService;
    this.discoveryCandidateDirectoryService = discoveryCandidateDirectoryService;
    this.discoveryObservabilityService = discoveryObservabilityService;
    this.discoveryService = discoveryService;
  }

  /**
   * Reads retry status for a previously queued discovery request.
   *
   * @param id queued request id
   * @return retry status payload
   */
  @GetMapping("/queued/{id}")
  public ResponseEntity<DiscoveryRetryStatusPayload> getQueuedStatus(
      @PathVariable("id") final String id) {
    try {
      return ResponseEntity.ok(
          DiscoveryRetryStatusPayload.fromDomain(discoveryRetryQueueService.get(id)));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.notFound().build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }

  /**
   * Cancels one queued discovery request explicitly.
   *
   * @param id queued request id
   * @param payload optional cancellation payload
   * @return updated retry status payload
   */
  @PostMapping("/queued/{id}/cancel")
  public ResponseEntity<DiscoveryRetryStatusPayload> cancelQueued(
      @PathVariable("id") final String id,
      @RequestBody(required = false) final DiscoveryRetryCancelPayload payload) {
    try {
      final String reason = payload == null ? null : payload.reason();
      return ResponseEntity.ok(
          DiscoveryRetryStatusPayload.fromDomain(discoveryRetryQueueService.cancel(id, reason)));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.notFound().build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }

  /**
   * Lists active discovery candidates.
   *
   * @return active candidates payload
   */
  @GetMapping("/candidates")
  public ResponseEntity<List<DiscoveryCandidatePayload>> getCandidates() {
    final List<DiscoveryCandidatePayload> payload =
        discoveryCandidateDirectoryService.findActiveCandidates().stream()
            .map(DiscoveryCandidatePayload::fromDomain)
            .toList();
    return ResponseEntity.ok(payload);
  }

  /**
   * Returns aggregated discovery metrics.
   *
   * @return metrics payload
   */
  @GetMapping("/metrics")
  public ResponseEntity<DiscoveryMetricsPayload> metrics() {
    return ResponseEntity.ok(
        DiscoveryMetricsPayload.fromSnapshot(discoveryObservabilityService.snapshot()));
  }

  /**
   * Creates or updates one discovery candidate profile.
   *
   * @param nodeId candidate node id
   * @param payload candidate profile payload
   * @return upserted candidate payload
   */
  @PutMapping("/candidates/{nodeId}")
  public ResponseEntity<DiscoveryCandidatePayload> upsertCandidate(
      @PathVariable("nodeId") final String nodeId,
      @RequestBody final DiscoveryCandidateUpsertPayload payload) {
    try {
      final DiscoveryCandidateProfile profile =
          new DiscoveryCandidateProfile(
              nodeId,
              payload.failureDomain(),
              payload.baseUrl(),
              payload.originalRequestedBucket(),
              payload.acceptedBuckets() == null ? Set.of() : payload.acceptedBuckets());
      discoveryCandidateDirectoryService.upsertCandidate(profile);
      return ResponseEntity.ok(DiscoveryCandidatePayload.fromDomain(profile));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }

  /**
   * Removes one discovery candidate profile.
   *
   * @param nodeId candidate node id
   * @return no content on success
   */
  @DeleteMapping("/candidates/{nodeId}")
  public ResponseEntity<Void> deleteCandidate(@PathVariable("nodeId") final String nodeId) {
    try {
      discoveryCandidateDirectoryService.removeCandidate(nodeId);
      return ResponseEntity.noContent().build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }

  /**
   * Inter-node discovery. Recibe firma signed automáticamente vía el interceptor registrado para
   * {@code /ops/**} en {@code SecurityModuleConfiguration}.
   *
   * <p>Si el directorio queda vacío para esta query, encola la request en {@code
   * DiscoveryRetryQueueService} y devuelve 200 con {@code queuedRequestId} en el body, preserva el
   * contrato del endpoint anterior.
   *
   * @param payload HTTP payload
   * @return candidate list with original bucket sizes y baseUrl, o queuedRequestId si vacío
   */
  @PostMapping("/query")
  public ResponseEntity<DiscoveryResponsePayload> query(
      @RequestBody final DiscoveryRequestPayload payload) {
    final DiscoveryRequest request =
        new DiscoveryRequest(
            payload.nodeId(),
            payload.failureDomain(),
            payload.requestedBucket(),
            payload.ratio(),
            payload.maxCandidates(),
            payload.targetFailureDomain(),
            payload.distributionPlan() == null ? java.util.Map.of() : payload.distributionPlan());

    final DiscoveryResponse response = discoveryService.discover(request);

    final List<DiscoveryResponsePayload.CandidateNodePayload> candidates =
        response.candidates().stream()
            .map(
                candidate ->
                    new DiscoveryResponsePayload.CandidateNodePayload(
                        candidate.nodeId(), candidate.originalBucketSize(), candidate.baseUrl()))
            .toList();

    if (!candidates.isEmpty()) {
      return ResponseEntity.ok(new DiscoveryResponsePayload(candidates));
    }

    final String queuedRequestId = discoveryRetryQueueService.enqueue(request).id();
    return ResponseEntity.ok(new DiscoveryResponsePayload(candidates, queuedRequestId));
  }
}
