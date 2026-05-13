package es.ual.node.recovery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ual.node.recovery.adapters.out.memory.InMemoryRecoveryOrphanFragmentPayloadPort;
import es.ual.node.recovery.adapters.out.memory.InMemoryRecoveryOrphanFragmentPort;
import es.ual.node.recovery.domain.RecoveryOrphanFragment;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Observability-focused tests for {@link RecoveryConsistencyService}. El servicio solo expone el
 * counter {@code reconciledMissingPayload}.
 */
class RecoveryConsistencyServiceObservabilityTest {

  @Test
  void processMaintenanceUpdatesObservabilityForSuccessfulCycle() {
    final InMemoryRecoveryOrphanFragmentPort port = new InMemoryRecoveryOrphanFragmentPort();
    final InMemoryRecoveryOrphanFragmentPayloadPort payloadPort =
        new InMemoryRecoveryOrphanFragmentPayloadPort();
    final RecoveryProperties properties = recoveryProperties();
    final RecoveryObservabilityService observabilityService = new RecoveryObservabilityService();

    final RecoveryOrphanFragment missingPayload = orphan("obs-orphan");
    port.save(missingPayload);

    final RecoveryConsistencyService service =
        new RecoveryConsistencyService(port, payloadPort, properties, observabilityService);

    final RecoveryConsistencyService.RecoveryConsistencyOutcome outcome =
        service.processMaintenance();
    final RecoveryMetricsSnapshot snapshot = observabilityService.snapshot();

    assertThat(outcome.reconciledMissingPayload()).isEqualTo(1);
    assertThat(snapshot.recoveryCleanupRunTotal()).isEqualTo(1);
    assertThat(snapshot.recoveryCleanupRunErrorTotal()).isZero();
    assertThat(snapshot.recoveryConsistencyReconciliationTotal()).isEqualTo(1);
  }

  @Test
  void processMaintenanceCountsRunErrorWhenUnexpectedExceptionOccurs() {
    final RecoveryProperties properties = recoveryProperties();
    final RecoveryObservabilityService observabilityService = new RecoveryObservabilityService();

    final RecoveryConsistencyService service =
        new RecoveryConsistencyService(
            new ThrowingPort(),
            new InMemoryRecoveryOrphanFragmentPayloadPort(),
            properties,
            observabilityService);

    assertThatThrownBy(service::processMaintenance)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated findAll failure");

    final RecoveryMetricsSnapshot snapshot = observabilityService.snapshot();
    assertThat(snapshot.recoveryCleanupRunTotal()).isEqualTo(1);
    assertThat(snapshot.recoveryCleanupRunErrorTotal()).isEqualTo(1);
    assertThat(snapshot.recoveryConsistencyReconciliationTotal()).isZero();
  }

  private RecoveryProperties recoveryProperties() {
    final RecoveryProperties properties = new RecoveryProperties();
    properties.getConsistency().setEnabled(true);
    properties.getConsistency().setReconciliationBatchSize(50);
    return properties;
  }

  private RecoveryOrphanFragment orphan(final String fragmentId) {
    final byte[] hash = new byte[32];
    return new RecoveryOrphanFragment(
        fragmentId,
        "agr-" + fragmentId,
        "node-a",
        "SHA-256",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        hash.length,
        Instant.parse("2026-04-18T13:00:00Z"));
  }

  private static final class ThrowingPort implements RecoveryOrphanFragmentPort {

    @Override
    public void save(final RecoveryOrphanFragment stored) {
      throw new UnsupportedOperationException("not required");
    }

    @Override
    public Optional<RecoveryOrphanFragment> findByFragmentId(final String fragmentId) {
      return Optional.empty();
    }

    @Override
    public List<RecoveryOrphanFragment> findByRequesterNodeId(final String requesterNodeId) {
      return List.of();
    }

    @Override
    public List<RecoveryOrphanFragment> findAll() {
      return List.of();
    }

    @Override
    public List<String> findAllFragmentIds(final int limit) {
      throw new IllegalStateException("simulated findAll failure");
    }

    @Override
    public void deleteByFragmentId(final String fragmentId) {
      throw new UnsupportedOperationException("not required");
    }
  }
}
