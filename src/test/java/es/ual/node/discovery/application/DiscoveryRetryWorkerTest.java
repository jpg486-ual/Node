package es.ual.node.discovery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import es.ual.node.discovery.adapters.out.memory.InMemoryDiscoveryCandidateDirectoryAdapter;
import es.ual.node.discovery.adapters.out.memory.InMemoryDiscoveryRetryQueuePort;
import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import es.ual.node.discovery.domain.DiscoveryRequest;
import es.ual.node.discovery.domain.DiscoveryRetryRequest;
import es.ual.node.discovery.domain.DiscoveryRetryStatus;
import es.ual.node.identitysecurity.adapters.out.memory.InMemoryPublicKeyRegistry;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests automatic retry processing and release from pending queue. */
class DiscoveryRetryWorkerTest {

  @Test
  void workerAutomaticallyRetriesAndResolvesWhenCandidatesAppear() throws Exception {
    final MutableClock clock = new MutableClock(Instant.parse("2026-03-07T18:00:00Z"));

    final InMemoryPublicKeyRegistry registry = new InMemoryPublicKeyRegistry();
    final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
    keyPairGenerator.initialize(256);
    registry.register("requester", keyPairGenerator.generateKeyPair().getPublic());

    final InMemoryDiscoveryCandidateDirectoryAdapter directory =
        new InMemoryDiscoveryCandidateDirectoryAdapter();
    final DiscoveryProperties discoveryProperties = new DiscoveryProperties();
    discoveryProperties.setFailureDomainFilterEnabled(false);
    final DiscoveryService discoveryService =
        new DiscoveryService(registry, directory, discoveryProperties);

    final DiscoveryRetryProperties retryProperties = new DiscoveryRetryProperties();
    retryProperties.setBaseDelaySeconds(5);
    retryProperties.setMaxDelaySeconds(30);
    retryProperties.setBatchSize(10);

    final DiscoveryRetryQueueService queueService =
        new DiscoveryRetryQueueService(
            new InMemoryDiscoveryRetryQueuePort(), retryProperties, clock);
    final DiscoveryRetryWorker worker = new DiscoveryRetryWorker(discoveryService, queueService);

    final DiscoveryRetryRequest queued =
        queueService.enqueue(new DiscoveryRequest("requester", "zone-a", 1024L, 1.0d, 10));

    worker.processDue();
    final DiscoveryRetryRequest afterFirstPass = queueService.get(queued.id());
    assertEquals(DiscoveryRetryStatus.PENDING, afterFirstPass.status());
    assertEquals(1, afterFirstPass.attemptCount());

    directory.upsert(
        new DiscoveryCandidateProfile(
            "node-a", "zone-a/rack-1", "http://node-a:8080", 1024L, Set.of()));
    clock.plusSeconds(5);

    worker.processDue();
    final DiscoveryRetryRequest resolved = queueService.get(queued.id());
    assertEquals(DiscoveryRetryStatus.RESOLVED, resolved.status());
    assertEquals(2, resolved.attemptCount());
    assertEquals(1, resolved.resolvedCandidateCount());
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(final Instant instant) {
      this.instant = instant;
    }

    private void plusSeconds(final long seconds) {
      this.instant = this.instant.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
