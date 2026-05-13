package es.ual.node.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.bootstrap.configuration.TestNodeIdentityKeys;
import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import es.ual.node.discovery.ports.out.DiscoveryCandidateDirectoryPort;
import es.ual.node.identitysecurity.ports.out.NonceStore;
import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.negotiation.domain.NegotiationAgreement;
import es.ual.node.negotiation.domain.NegotiationStatus;
import es.ual.node.negotiation.domain.TransferAuthorizationToken;
import es.ual.node.negotiation.domain.TransferMode;
import es.ual.node.negotiation.ports.out.AgreementRepository;
import es.ual.node.negotiation.ports.out.CapacityPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresCapacityPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresDiscoveryCandidateDirectoryPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresRecoveryOrphanFragmentPayloadPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresRecoveryOrphanFragmentPort;
import es.ual.node.recovery.domain.RecoveryOrphanFragment;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPayloadPort;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPort;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Integration tests for persistence wiring in postgres mode. */
@SpringBootTest(
    properties = {
      "node.persistence.mode=postgres",
      "node.features.discovery-enabled=false",
      "node.features.negotiation-enabled=false",
      "node.features.custody-enabled=false",
      "node.features.recovery-enabled=false",
      "node.capacity.max-bytes=100",
      "node.topology.tutorAcceptedPublicKeys=test-public-key"
    })
class PersistencePostgresModeIntegrationTest {

  private static final String[] NODE_IDENTITY_PROPERTIES =
      TestNodeIdentityKeys.generatePropertyValues();

  @Autowired private AgreementRepository agreementRepository;

  @Autowired private NonceStore nonceStore;

  @Autowired private CapacityPort capacityPort;

  @Autowired private RecoveryOrphanFragmentPort recoveryOrphanFragmentPort;

  @Autowired private RecoveryOrphanFragmentPayloadPort recoveryOrphanFragmentPayloadPort;

  @Autowired private DiscoveryCandidateDirectoryPort discoveryCandidateDirectoryPort;

  @DynamicPropertySource
  static void configureNodeIdentity(final DynamicPropertyRegistry registry) {
    for (String property : NODE_IDENTITY_PROPERTIES) {
      int separatorIndex = property.indexOf('=');
      String key = property.substring(0, separatorIndex);
      String value = property.substring(separatorIndex + 1);
      registry.add(key, () -> value);
    }
  }

  @Test
  void agreementRepositoryPersistsAndLoadsAgreement() {
    Instant now = Instant.now();
    NegotiationAgreement agreement =
        new NegotiationAgreement(
            "agr-pg-1",
            "node-a",
            "node-b",
            NegotiationStatus.CONFIRMED,
            TransferMode.FRAGMENTS_ONLY,
            1024L,
            4096L,
            4,
            "RS(6,4)",
            6400L,
            validManifest(),
            now,
            now.plusSeconds(120),
            "requester-signature",
            "target-signature",
            new TransferAuthorizationToken("token-1", "agr-pg-1", now, now.plusSeconds(120)));

    agreementRepository.save(agreement);
    NegotiationAgreement loaded = agreementRepository.findById("agr-pg-1").orElseThrow();

    assertEquals(NegotiationStatus.CONFIRMED, loaded.status());
    assertEquals("node-a", loaded.requesterNodeId());
    assertEquals("token-1", loaded.transferAuthorizationToken().token());
    // El blob fileManifest es null post-load (V35 destructiva); el file_id sí se preserva
    // como columna independiente.
    assertNull(loaded.fileManifest());
    assertEquals(agreement.fileId(), loaded.fileId());
  }

  @Test
  void nonceStoreRejectsReplayNonce() {
    Instant expiresAt = Instant.now().plusSeconds(60);

    boolean first = nonceStore.markIfAbsent("nonce-pg-1", expiresAt);
    boolean second = nonceStore.markIfAbsent("nonce-pg-1", expiresAt.plusSeconds(10));

    assertTrue(first);
    assertFalse(second);
  }

  @Test
  void discoveryCandidateDirectoryPortPersistsAndLoadsActiveCandidates() {
    assertTrue(discoveryCandidateDirectoryPort instanceof PostgresDiscoveryCandidateDirectoryPort);

    discoveryCandidateDirectoryPort.removeCandidate("candidate-pg-1");
    discoveryCandidateDirectoryPort.upsertCandidate(
        new DiscoveryCandidateProfile(
            "candidate-pg-1",
            "zone-a/rack-1",
            "http://candidate-pg-1:8080",
            2048L,
            Set.of(1024L, 2048L)));

    assertTrue(
        discoveryCandidateDirectoryPort.findActiveCandidates().stream()
            .anyMatch(candidate -> "candidate-pg-1".equals(candidate.nodeId())));

    discoveryCandidateDirectoryPort.removeCandidate("candidate-pg-1");
    assertFalse(
        discoveryCandidateDirectoryPort.findActiveCandidates().stream()
            .anyMatch(candidate -> "candidate-pg-1".equals(candidate.nodeId())));
  }

  @Test
  void capacityPortPersistsDurableLedgerAndRespectsLimits() {
    assertTrue(capacityPort instanceof PostgresCapacityPort);
    assertTrue(capacityPort.canReserve(100L));
    assertFalse(capacityPort.canReserve(101L));

    capacityPort.reserve("cap-pg-1", 60L);
    capacityPort.commit("cap-pg-1");

    assertTrue(capacityPort.canReserve(40L));
    assertFalse(capacityPort.canReserve(41L));

    assertDoesNotThrow(() -> capacityPort.reserve("cap-pg-1", 60L));
    assertThrows(IllegalArgumentException.class, () -> capacityPort.reserve("cap-pg-1", 61L));
    assertThrows(IllegalArgumentException.class, () -> capacityPort.reserve("cap-pg-2", 41L));

    capacityPort.release("cap-pg-1");
    capacityPort.release("cap-pg-1");

    assertTrue(capacityPort.canReserve(100L));
    assertThrows(IllegalArgumentException.class, () -> capacityPort.reserve("cap-pg-1", 60L));
  }

  @Test
  void recoveryOrphanFragmentPortPersistsAndLoadsFragment() {
    assertTrue(recoveryOrphanFragmentPort instanceof PostgresRecoveryOrphanFragmentPort);

    Instant now = Instant.now();
    RecoveryOrphanFragment stored =
        new RecoveryOrphanFragment(
            "frag-pg-1",
            "agr-pg-1",
            "node-a",
            "SHA-256",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            5,
            now);

    recoveryOrphanFragmentPort.save(stored);
    RecoveryOrphanFragment loaded =
        recoveryOrphanFragmentPort.findByFragmentId("frag-pg-1").orElseThrow();

    assertEquals("agr-pg-1", loaded.agreementId());
    assertEquals("node-a", loaded.requesterNodeId());
    assertEquals(5, loaded.sizeBytes());
    assertEquals("SHA-256", loaded.checksumAlgorithm());
  }

  @Test
  void recoveryConsistencyQueryExposesAllFragmentIds() {
    // Orphan fragments NUNCA se purgan por TTL. Solo el listado completo se mantiene.
    assertTrue(recoveryOrphanFragmentPort instanceof PostgresRecoveryOrphanFragmentPort);

    final Instant now = Instant.now();
    final String firstId = "frag-consistency-first-" + now.toEpochMilli();
    final String secondId = "frag-consistency-second-" + now.toEpochMilli();

    recoveryOrphanFragmentPort.save(
        new RecoveryOrphanFragment(
            firstId,
            "agr-consistency-first",
            "node-a",
            "SHA-256",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            5,
            now.minusSeconds(20)));
    recoveryOrphanFragmentPort.save(
        new RecoveryOrphanFragment(
            secondId,
            "agr-consistency-second",
            "node-a",
            "SHA-256",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            5,
            now.minusSeconds(120)));

    final List<String> allIds = recoveryOrphanFragmentPort.findAllFragmentIds(50);

    assertTrue(allIds.contains(firstId));
    assertTrue(allIds.contains(secondId));
  }

  @Test
  void recoveryOrphanFragmentPayloadPortExistsReflectsStoredPayload() {
    assertTrue(
        recoveryOrphanFragmentPayloadPort instanceof PostgresRecoveryOrphanFragmentPayloadPort);

    final Instant now = Instant.now();
    final String fragmentId = "frag-payload-exists-" + now.toEpochMilli();

    recoveryOrphanFragmentPort.save(
        new RecoveryOrphanFragment(
            fragmentId,
            "agr-payload-exists",
            "node-a",
            "SHA-256",
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            3,
            now));

    recoveryOrphanFragmentPayloadPort.save(fragmentId, new byte[] {1, 2, 3});

    assertTrue(recoveryOrphanFragmentPayloadPort.exists(fragmentId));

    recoveryOrphanFragmentPayloadPort.deleteByFragmentId(fragmentId);
    assertFalse(recoveryOrphanFragmentPayloadPort.exists(fragmentId));
  }

  private FileManifest validManifest() {
    return new FileManifest(
        "0d7f64c2-97cc-4400-a2a3-b3af056f85a1",
        "/test",
        "dataset.bin",
        100_000L,
        90_000L,
        "zstd",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        4,
        25000L,
        6,
        4,
        List.of("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
  }
}
