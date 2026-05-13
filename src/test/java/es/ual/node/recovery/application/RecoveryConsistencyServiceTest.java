package es.ual.node.recovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import es.ual.node.recovery.adapters.out.memory.InMemoryRecoveryOrphanFragmentPayloadPort;
import es.ual.node.recovery.adapters.out.memory.InMemoryRecoveryOrphanFragmentPort;
import es.ual.node.recovery.domain.RecoveryOrphanFragment;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RecoveryConsistencyService}. El servicio queda reducido a la reconciliación
 * metadata-without-payload; la limpieza por TTL fue eliminada.
 */
class RecoveryConsistencyServiceTest {

  @Test
  void processMaintenanceSkipsWhenDisabled() {
    final InMemoryRecoveryOrphanFragmentPort port = new InMemoryRecoveryOrphanFragmentPort();
    final InMemoryRecoveryOrphanFragmentPayloadPort payloadPort =
        new InMemoryRecoveryOrphanFragmentPayloadPort();
    final RecoveryProperties properties = recoveryProperties();
    properties.getConsistency().setEnabled(false);

    final RecoveryOrphanFragment fragment = orphan("frag-disabled");
    port.save(fragment);
    payloadPort.save(fragment.fragmentId(), "payload".getBytes(StandardCharsets.UTF_8));

    final RecoveryConsistencyService service =
        new RecoveryConsistencyService(port, payloadPort, properties);

    final RecoveryConsistencyService.RecoveryConsistencyOutcome outcome =
        service.processMaintenance();

    assertThat(outcome.reconciledMissingPayload()).isZero();
    assertThat(port.findByFragmentId(fragment.fragmentId())).isPresent();
    assertThat(payloadPort.findByFragmentId(fragment.fragmentId())).isPresent();
  }

  @Test
  void processMaintenanceReconcilesMetadataWithoutPayload() {
    final InMemoryRecoveryOrphanFragmentPort port = new InMemoryRecoveryOrphanFragmentPort();
    final InMemoryRecoveryOrphanFragmentPayloadPort payloadPort =
        new InMemoryRecoveryOrphanFragmentPayloadPort();
    final RecoveryProperties properties = recoveryProperties();

    final RecoveryOrphanFragment missingPayload = orphan("frag-missing-payload");
    final RecoveryOrphanFragment healthy = orphan("frag-healthy");
    port.save(missingPayload);
    port.save(healthy);
    payloadPort.save(healthy.fragmentId(), "healthy".getBytes(StandardCharsets.UTF_8));

    final RecoveryConsistencyService service =
        new RecoveryConsistencyService(port, payloadPort, properties);

    final RecoveryConsistencyService.RecoveryConsistencyOutcome outcome =
        service.processMaintenance();

    assertThat(outcome.reconciledMissingPayload()).isEqualTo(1);
    assertThat(port.findByFragmentId(missingPayload.fragmentId())).isEmpty();
    assertThat(port.findByFragmentId(healthy.fragmentId())).isPresent();
    assertThat(payloadPort.findByFragmentId(healthy.fragmentId())).isPresent();
  }

  @Test
  void processMaintenanceLeavesOrphansUntouchedWhenPayloadPresent() {
    // Invariante: orphan fragments NUNCA se purgan por TTL.
    final InMemoryRecoveryOrphanFragmentPort port = new InMemoryRecoveryOrphanFragmentPort();
    final InMemoryRecoveryOrphanFragmentPayloadPort payloadPort =
        new InMemoryRecoveryOrphanFragmentPayloadPort();
    final RecoveryProperties properties = recoveryProperties();

    final RecoveryOrphanFragment veryOldOrphan = orphan("frag-very-old");
    port.save(veryOldOrphan);
    payloadPort.save(veryOldOrphan.fragmentId(), "old-bytes".getBytes(StandardCharsets.UTF_8));

    final RecoveryConsistencyService service =
        new RecoveryConsistencyService(port, payloadPort, properties);

    service.processMaintenance();

    assertThat(port.findByFragmentId(veryOldOrphan.fragmentId())).isPresent();
    assertThat(payloadPort.findByFragmentId(veryOldOrphan.fragmentId())).isPresent();
  }

  private RecoveryProperties recoveryProperties() {
    final RecoveryProperties properties = new RecoveryProperties();
    properties.getConsistency().setEnabled(true);
    properties.getConsistency().setReconciliationBatchSize(50);
    return properties;
  }

  private RecoveryOrphanFragment orphan(final String fragmentId) {
    return new RecoveryOrphanFragment(
        fragmentId,
        "agr-" + fragmentId,
        "node-a",
        "SHA-256",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        8,
        Instant.parse("2026-04-18T11:00:00Z"));
  }
}
