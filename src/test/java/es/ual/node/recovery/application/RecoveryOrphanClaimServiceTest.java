package es.ual.node.recovery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ual.node.recovery.adapters.out.memory.InMemoryRecoveryOrphanFragmentPayloadPort;
import es.ual.node.recovery.adapters.out.memory.InMemoryRecoveryOrphanFragmentPort;
import es.ual.node.recovery.domain.RecoveryOrphanFragment;
import java.time.Instant;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RecoveryOrphanClaimService}. Origen recovered claim+ACK + admin DELETE; orphan
 * fragments NUNCA se purgan por TTL.
 */
class RecoveryOrphanClaimServiceTest {

  private static final String FRAGMENT_ID = "frag-1";
  private static final String OWNER = "node-owner";
  private static final String STRANGER = "node-stranger";

  @Test
  void claim_returnsBytesAndDoesNotDelete() {
    final InMemoryRecoveryOrphanFragmentPort orphanPort = new InMemoryRecoveryOrphanFragmentPort();
    final InMemoryRecoveryOrphanFragmentPayloadPort payloadPort =
        new InMemoryRecoveryOrphanFragmentPayloadPort();
    seed(orphanPort, payloadPort, FRAGMENT_ID, OWNER, "secret".getBytes());

    final RecoveryOrphanClaimService service =
        new RecoveryOrphanClaimService(orphanPort, payloadPort);

    final RecoveryOrphanClaimService.ClaimResult result = service.claim(FRAGMENT_ID, OWNER);

    assertThat(result.payload()).isEqualTo("secret".getBytes());
    assertThat(result.orphan().requesterNodeId()).isEqualTo(OWNER);
    // Tutor NO borra en el claim — protege contra descarga interrumpida.
    assertThat(orphanPort.findByFragmentId(FRAGMENT_ID)).isPresent();
    assertThat(payloadPort.findByFragmentId(FRAGMENT_ID)).isPresent();
  }

  @Test
  void claim_throwsSecurityWhenCallerDoesNotOwn() {
    final InMemoryRecoveryOrphanFragmentPort orphanPort = new InMemoryRecoveryOrphanFragmentPort();
    final InMemoryRecoveryOrphanFragmentPayloadPort payloadPort =
        new InMemoryRecoveryOrphanFragmentPayloadPort();
    seed(orphanPort, payloadPort, FRAGMENT_ID, OWNER, "x".getBytes());

    final RecoveryOrphanClaimService service =
        new RecoveryOrphanClaimService(orphanPort, payloadPort);

    assertThatThrownBy(() -> service.claim(FRAGMENT_ID, STRANGER))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("does not own");
    assertThat(orphanPort.findByFragmentId(FRAGMENT_ID)).isPresent();
  }

  @Test
  void claim_throwsNotFoundWhenOrphanAbsent() {
    final RecoveryOrphanClaimService service =
        new RecoveryOrphanClaimService(
            new InMemoryRecoveryOrphanFragmentPort(),
            new InMemoryRecoveryOrphanFragmentPayloadPort());

    assertThatThrownBy(() -> service.claim(FRAGMENT_ID, OWNER))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining("orphan not found");
  }

  @Test
  void claim_throwsWhenPayloadMissingButMetadataPresent() {
    final InMemoryRecoveryOrphanFragmentPort orphanPort = new InMemoryRecoveryOrphanFragmentPort();
    orphanPort.save(orphan(FRAGMENT_ID, OWNER));
    final RecoveryOrphanClaimService service =
        new RecoveryOrphanClaimService(orphanPort, new InMemoryRecoveryOrphanFragmentPayloadPort());

    assertThatThrownBy(() -> service.claim(FRAGMENT_ID, OWNER))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining("payload missing");
  }

  @Test
  void ack_deletesMetadataAndPayloadWhenOwnerConfirms() {
    final InMemoryRecoveryOrphanFragmentPort orphanPort = new InMemoryRecoveryOrphanFragmentPort();
    final InMemoryRecoveryOrphanFragmentPayloadPort payloadPort =
        new InMemoryRecoveryOrphanFragmentPayloadPort();
    seed(orphanPort, payloadPort, FRAGMENT_ID, OWNER, "secret".getBytes());

    final RecoveryOrphanClaimService service =
        new RecoveryOrphanClaimService(orphanPort, payloadPort);
    service.ack(FRAGMENT_ID, OWNER);

    assertThat(orphanPort.findByFragmentId(FRAGMENT_ID)).isEmpty();
    assertThat(payloadPort.findByFragmentId(FRAGMENT_ID)).isEmpty();
  }

  @Test
  void ack_isIdempotentForAbsentOrphan() {
    final RecoveryOrphanClaimService service =
        new RecoveryOrphanClaimService(
            new InMemoryRecoveryOrphanFragmentPort(),
            new InMemoryRecoveryOrphanFragmentPayloadPort());

    // No lanza — ACK idempotente.
    service.ack(FRAGMENT_ID, OWNER);
  }

  @Test
  void ack_throwsSecurityWhenCallerDoesNotOwn() {
    final InMemoryRecoveryOrphanFragmentPort orphanPort = new InMemoryRecoveryOrphanFragmentPort();
    final InMemoryRecoveryOrphanFragmentPayloadPort payloadPort =
        new InMemoryRecoveryOrphanFragmentPayloadPort();
    seed(orphanPort, payloadPort, FRAGMENT_ID, OWNER, "x".getBytes());

    final RecoveryOrphanClaimService service =
        new RecoveryOrphanClaimService(orphanPort, payloadPort);

    assertThatThrownBy(() -> service.ack(FRAGMENT_ID, STRANGER))
        .isInstanceOf(SecurityException.class);
    // Orphan NO borrado.
    assertThat(orphanPort.findByFragmentId(FRAGMENT_ID)).isPresent();
  }

  @Test
  void adminDelete_returnsTrueWhenExists() {
    final InMemoryRecoveryOrphanFragmentPort orphanPort = new InMemoryRecoveryOrphanFragmentPort();
    final InMemoryRecoveryOrphanFragmentPayloadPort payloadPort =
        new InMemoryRecoveryOrphanFragmentPayloadPort();
    seed(orphanPort, payloadPort, FRAGMENT_ID, OWNER, "x".getBytes());

    final RecoveryOrphanClaimService service =
        new RecoveryOrphanClaimService(orphanPort, payloadPort);

    assertThat(service.adminDelete(FRAGMENT_ID)).isTrue();
    assertThat(orphanPort.findByFragmentId(FRAGMENT_ID)).isEmpty();
    assertThat(payloadPort.findByFragmentId(FRAGMENT_ID)).isEmpty();
  }

  @Test
  void adminDelete_returnsFalseWhenAbsent() {
    final RecoveryOrphanClaimService service =
        new RecoveryOrphanClaimService(
            new InMemoryRecoveryOrphanFragmentPort(),
            new InMemoryRecoveryOrphanFragmentPayloadPort());

    assertThat(service.adminDelete(FRAGMENT_ID)).isFalse();
  }

  private void seed(
      final InMemoryRecoveryOrphanFragmentPort orphanPort,
      final InMemoryRecoveryOrphanFragmentPayloadPort payloadPort,
      final String fragmentId,
      final String owner,
      final byte[] payload) {
    orphanPort.save(orphan(fragmentId, owner));
    payloadPort.save(fragmentId, payload);
  }

  private RecoveryOrphanFragment orphan(final String fragmentId, final String requesterNodeId) {
    return new RecoveryOrphanFragment(
        fragmentId,
        "agr-" + fragmentId,
        requesterNodeId,
        "SHA-256",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        6,
        Instant.parse("2026-05-04T10:00:00Z"));
  }
}
