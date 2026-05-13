package es.ual.node.discovery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import es.ual.node.discovery.domain.DiscoveryRequest;
import es.ual.node.discovery.domain.DiscoveryResponse;
import es.ual.node.discovery.ports.out.DiscoveryCandidateDirectoryPort;
import es.ual.node.identitysecurity.adapters.out.memory.InMemoryPublicKeyRegistry;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DiscoveryService}. */
class DiscoveryServiceTest {

  private static final String REQUESTER_NODE_ID = "requester";

  private InMemoryPublicKeyRegistry publicKeyRegistry;
  private InMemoryCandidateDirectory directory;
  private DiscoveryService service;

  @BeforeEach
  void setUp() throws Exception {
    publicKeyRegistry = new InMemoryPublicKeyRegistry();
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
    keyPairGenerator.initialize(256);
    KeyPair keyPair = keyPairGenerator.generateKeyPair();
    publicKeyRegistry.register(REQUESTER_NODE_ID, keyPair.getPublic());

    directory = new InMemoryCandidateDirectory();

    DiscoveryProperties properties = new DiscoveryProperties();
    properties.setMaxRatio(1.25d);
    properties.setMaxCandidatesLimit(50);
    properties.setFailureDomainFilterEnabled(true);

    service = new DiscoveryService(publicKeyRegistry, directory, properties);
  }

  private static DiscoveryCandidateProfile profile(
      final String nodeId,
      final String failureDomain,
      final long originalRequestedBucket,
      final Set<Long> acceptedBuckets) {
    return new DiscoveryCandidateProfile(
        nodeId,
        failureDomain,
        "http://" + nodeId + ":8080",
        originalRequestedBucket,
        acceptedBuckets);
  }

  @Test
  void registeredNodeDiscoversExactBucketCandidates() {
    directory.setCandidates(
        List.of(
            profile("node-a", "zone-a/rack-1", 1024L, Set.of(1024L)),
            profile("node-b", "zone-a/rack-2", 2048L, Set.of(1024L)),
            profile("node-c", "zone-a/rack-3", 3072L, Set.of(3072L))));

    DiscoveryResponse response =
        service.discover(new DiscoveryRequest(REQUESTER_NODE_ID, "zone-a", 1024L, 1.0d, 10));

    assertEquals(2, response.candidates().size());
    assertTrue(
        response.candidates().stream().anyMatch(candidate -> candidate.nodeId().equals("node-a")));
    assertTrue(
        response.candidates().stream().anyMatch(candidate -> candidate.nodeId().equals("node-b")));
  }

  @Test
  void registeredNodeDiscoversExpandedCandidatesUsingRatio() {
    directory.setCandidates(
        List.of(
            profile("node-a", "zone-a/rack-1", 1024L, Set.of()),
            profile("node-b", "zone-a/rack-2", 1200L, Set.of()),
            profile("node-c", "zone-a/rack-3", 1300L, Set.of())));

    DiscoveryResponse response =
        service.discover(new DiscoveryRequest(REQUESTER_NODE_ID, "zone-a", 1000L, 1.2d, 10));

    assertEquals(2, response.candidates().size());
    assertTrue(
        response.candidates().stream().anyMatch(candidate -> candidate.nodeId().equals("node-a")));
    assertTrue(
        response.candidates().stream().anyMatch(candidate -> candidate.nodeId().equals("node-b")));
  }

  @Test
  void candidateListRespectsMaxCandidates() {
    directory.setCandidates(
        List.of(
            profile("node-a", "zone-a/rack-1", 1024L, Set.of()),
            profile("node-b", "zone-a/rack-2", 1024L, Set.of()),
            profile("node-c", "zone-a/rack-3", 1024L, Set.of())));

    DiscoveryResponse response =
        service.discover(new DiscoveryRequest(REQUESTER_NODE_ID, "zone-a", 1024L, 1.0d, 2));

    assertEquals(2, response.candidates().size());
  }

  @Test
  void bucketAndFailureDomainFilteringWorkCorrectly() {
    directory.setCandidates(
        List.of(
            profile("node-a", "zone-a/rack-1", 1024L, Set.of()),
            profile("node-b", "zone-b/rack-1", 1024L, Set.of()),
            profile("node-c", "zone-a/rack-2", 2048L, Set.of())));

    DiscoveryResponse response =
        service.discover(new DiscoveryRequest(REQUESTER_NODE_ID, "zone-a", 1024L, 1.0d, 10));

    assertEquals(1, response.candidates().size());
    assertEquals("node-a", response.candidates().getFirst().nodeId());
  }

  @Test
  void candidateResponseIncludesOriginalRequestedBucketForEachNode() {
    directory.setCandidates(List.of(profile("node-a", "zone-a/rack-1", 1024L, Set.of())));

    DiscoveryResponse response =
        service.discover(new DiscoveryRequest(REQUESTER_NODE_ID, "zone-a", 1024L, 1.0d, 10));

    assertEquals(1, response.candidates().size());
    assertEquals(1024L, response.candidates().getFirst().originalBucketSize());
  }

  @Test
  void candidateResponseIncludesBaseUrlForEachNode() {
    directory.setCandidates(List.of(profile("node-a", "zone-a/rack-1", 1024L, Set.of())));

    DiscoveryResponse response =
        service.discover(new DiscoveryRequest(REQUESTER_NODE_ID, "zone-a", 1024L, 1.0d, 10));

    assertEquals(1, response.candidates().size());
    assertEquals("http://node-a:8080", response.candidates().getFirst().baseUrl());
  }

  @Test
  void unregisteredNodeIsRejected() {
    assertThrows(
        DiscoveryException.class,
        () -> service.discover(new DiscoveryRequest("unknown", "zone-a", 1024L, 1.0d, 10)));
  }

  @Test
  void requestCanSelectCandidatesFromSpecificTargetFailureDomain() {
    directory.setCandidates(
        List.of(
            profile("rack1-a", "zone-a/rack-1", 1024L, Set.of()),
            profile("rack2-a", "zone-a/rack-2", 1024L, Set.of())));

    DiscoveryResponse response =
        service.discover(
            new DiscoveryRequest(
                REQUESTER_NODE_ID, "zone-a", 1024L, 1.0d, 10, "zone-a/rack-1", Map.of()));

    assertEquals(1, response.candidates().size());
    assertEquals("rack1-a", response.candidates().getFirst().nodeId());
  }

  @Test
  void requestCanApplyDistributionPlanAcrossFailureDomains() {
    directory.setCandidates(
        List.of(
            profile("a-1", "corp/a/r1", 1024L, Set.of()),
            profile("a-2", "corp/a/r2", 1024L, Set.of()),
            profile("a-3", "corp/a/r3", 1024L, Set.of()),
            profile("a-4", "corp/a/r4", 1024L, Set.of()),
            profile("b-1", "corp/b/r1", 1024L, Set.of()),
            profile("b-2", "corp/b/r2", 1024L, Set.of()),
            profile("b-3", "corp/b/r3", 1024L, Set.of()),
            profile("b-4", "corp/b/r4", 1024L, Set.of()),
            profile("c-1", "corp/c/r1", 1024L, Set.of()),
            profile("c-2", "corp/c/r2", 1024L, Set.of()),
            profile("c-3", "corp/c/r3", 1024L, Set.of()),
            profile("c-4", "corp/c/r4", 1024L, Set.of())));

    DiscoveryResponse response =
        service.discover(
            new DiscoveryRequest(
                REQUESTER_NODE_ID,
                "corp",
                1024L,
                1.0d,
                20,
                null,
                Map.of("corp/a", 3, "corp/b", 3, "corp/c", 4)));

    assertEquals(10, response.candidates().size());
    assertEquals(
        3,
        response.candidates().stream()
            .filter(candidate -> candidate.nodeId().startsWith("a-"))
            .count());
    assertEquals(
        3,
        response.candidates().stream()
            .filter(candidate -> candidate.nodeId().startsWith("b-"))
            .count());
    assertEquals(
        4,
        response.candidates().stream()
            .filter(candidate -> candidate.nodeId().startsWith("c-"))
            .count());
  }

  @Test
  void discoveryCanRequireTargetFailureDomainInRequests() {
    DiscoveryProperties strictProperties = new DiscoveryProperties();
    strictProperties.setMaxRatio(1.25d);
    strictProperties.setMaxCandidatesLimit(50);
    strictProperties.setFailureDomainFilterEnabled(true);
    strictProperties.setRequireTargetFailureDomain(true);
    DiscoveryService strictService =
        new DiscoveryService(publicKeyRegistry, directory, strictProperties);

    assertThrows(
        DiscoveryException.class,
        () ->
            strictService.discover(
                new DiscoveryRequest(REQUESTER_NODE_ID, "zone-a", 1024L, 1.0d, 10)));
  }

  @Test
  void discoveryCanRequireDistributionPlanInRequests() {
    DiscoveryProperties strictProperties = new DiscoveryProperties();
    strictProperties.setMaxRatio(1.25d);
    strictProperties.setMaxCandidatesLimit(50);
    strictProperties.setFailureDomainFilterEnabled(true);
    strictProperties.setRequireDistributionPlan(true);
    DiscoveryService strictService =
        new DiscoveryService(publicKeyRegistry, directory, strictProperties);

    assertThrows(
        DiscoveryException.class,
        () ->
            strictService.discover(
                new DiscoveryRequest(REQUESTER_NODE_ID, "zone-a", 1024L, 1.0d, 10)));
  }

  /** Test double for candidate directory port. */
  private static final class InMemoryCandidateDirectory implements DiscoveryCandidateDirectoryPort {

    private List<DiscoveryCandidateProfile> candidates = List.of();

    /**
     * Replaces candidate list used in tests.
     *
     * @param candidates candidate profiles
     */
    public void setCandidates(final List<DiscoveryCandidateProfile> candidates) {
      this.candidates = List.copyOf(candidates);
    }

    /** {@inheritDoc} */
    @Override
    public List<DiscoveryCandidateProfile> findActiveCandidates() {
      return candidates;
    }

    /** {@inheritDoc} */
    @Override
    public long countActiveCandidates() {
      return candidates.size();
    }

    /** {@inheritDoc} */
    @Override
    public void upsertCandidate(final DiscoveryCandidateProfile profile) {
      final java.util.ArrayList<DiscoveryCandidateProfile> updated =
          new java.util.ArrayList<>(candidates);
      updated.removeIf(existing -> existing.nodeId().equals(profile.nodeId()));
      updated.add(profile);
      candidates = List.copyOf(updated);
    }

    /** {@inheritDoc} */
    @Override
    public void removeCandidate(final String nodeId) {
      final java.util.ArrayList<DiscoveryCandidateProfile> updated =
          new java.util.ArrayList<>(candidates);
      updated.removeIf(existing -> existing.nodeId().equals(nodeId));
      candidates = List.copyOf(updated);
    }

    /** {@inheritDoc} */
    @Override
    public int deleteStale(final Instant staleBefore) {
      return 0;
    }
  }
}
