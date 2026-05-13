package es.ual.node.discovery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import es.ual.node.discovery.domain.DiscoveryRetryStatus;
import es.ual.node.discovery.ports.out.DiscoveryCandidateDirectoryPort;
import es.ual.node.discovery.ports.out.DiscoveryRetryQueuePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link DiscoveryObservabilityService}. */
@ExtendWith(MockitoExtension.class)
class DiscoveryObservabilityServiceTest {

  @Mock private DiscoveryRetryQueuePort retryQueuePort;

  @Mock private DiscoveryCandidateDirectoryPort candidateDirectoryPort;

  private DiscoveryObservabilityService service;

  @BeforeEach
  void setUp() {
    service = new DiscoveryObservabilityService(retryQueuePort, candidateDirectoryPort);
  }

  @Test
  void snapshotReturnsCurrentCounts() {
    when(retryQueuePort.countByStatus(DiscoveryRetryStatus.PENDING)).thenReturn(7L);
    when(retryQueuePort.countByStatus(DiscoveryRetryStatus.RESOLVED)).thenReturn(11L);
    when(retryQueuePort.countByStatus(DiscoveryRetryStatus.FAILED)).thenReturn(2L);
    when(candidateDirectoryPort.countActiveCandidates()).thenReturn(5L);

    final DiscoveryMetricsSnapshot snapshot = service.snapshot();

    assertEquals(7L, snapshot.pendingQueueCount());
    assertEquals(11L, snapshot.resolvedQueueCount());
    assertEquals(2L, snapshot.failedQueueCount());
    assertEquals(5L, snapshot.activeCandidatesCount());
    assertEquals(20L, snapshot.asNamedMetrics().get("discovery.queue.total.count"));
  }
}
