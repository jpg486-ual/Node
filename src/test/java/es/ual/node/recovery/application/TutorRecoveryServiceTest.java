package es.ual.node.recovery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.recovery.adapters.out.memory.InMemoryRecoveryOrphanFragmentPayloadPort;
import es.ual.node.recovery.adapters.out.memory.InMemoryRecoveryOrphanFragmentPort;
import es.ual.node.recovery.domain.RecoveryOrphanFragment;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPayloadPort;
import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsCodecAdapter;
import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsIntegrityVerifier;
import es.ual.node.reedsolomon.domain.RsFragment;
import es.ual.node.reedsolomon.domain.RsScheme;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * Tests for tutor recovery service. Orphan fragments NUNCA se purgan por TTL, solo via claim+ACK
 * del origen recovered o admin DELETE.
 */
class TutorRecoveryServiceTest {

  private static final String HELLO_SHA256 =
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

  @Test
  void rejectsStoreWhenChecksumDoesNotMatchPayload() {
    final TutorRecoveryService service = newService(clockAt("2026-03-06T18:00:00Z"));

    assertThatThrownBy(
            () ->
                service.store(
                    new TutorRecoveryService.StoreRecoveryFragmentRequest(
                        "fragment-bad-checksum",
                        "agreement-1",
                        "node-a",
                        "allowed-key",
                        "SHA-256",
                        "deadbeef",
                        "aGVsbG8=")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checksum does not match payload");
  }

  @Test
  void acceptsStoreWhenChecksumMatchesPayload() {
    final TutorRecoveryService service = newService(clockAt("2026-03-06T18:00:00Z"));

    final RecoveryOrphanFragment stored =
        service.store(
            new TutorRecoveryService.StoreRecoveryFragmentRequest(
                "fragment-good-checksum",
                "agreement-1",
                "node-a",
                "allowed-key",
                "SHA-256",
                HELLO_SHA256,
                "aGVsbG8="));

    assertThat(stored.fragmentId()).isEqualTo("fragment-good-checksum");
  }

  @Test
  void storesFragmentWhenRequesterKeyIsWhitelisted() {
    final InMemoryRecoveryOrphanFragmentPayloadPort payloadPort =
        new InMemoryRecoveryOrphanFragmentPayloadPort();
    final TutorRecoveryService service = newService(clockAt("2026-03-06T18:00:00Z"), payloadPort);

    final RecoveryOrphanFragment stored =
        service.store(
            new TutorRecoveryService.StoreRecoveryFragmentRequest(
                "fragment-1",
                "agreement-1",
                "node-a",
                "allowed-key",
                "SHA-256",
                HELLO_SHA256,
                "aGVsbG8="));

    assertThat(stored.fragmentId()).isEqualTo("fragment-1");
    assertThat(stored.sizeBytes()).isEqualTo(5);
    assertThat(stored.storedAt()).isEqualTo(Instant.parse("2026-03-06T18:00:00Z"));

    assertThat(service.get("fragment-1").agreementId()).isEqualTo("agreement-1");
    assertThat(service.getContent("fragment-1")).isEqualTo("hello".getBytes());
    assertThat(payloadPort.findByFragmentId("fragment-1")).isPresent();
  }

  @Test
  void rejectsStoreWhenRequesterKeyIsNotWhitelisted() {
    final TutorRecoveryService service = newService(clockAt("2026-03-06T18:00:00Z"));

    assertThatThrownBy(
            () ->
                service.store(
                    new TutorRecoveryService.StoreRecoveryFragmentRequest(
                        "fragment-1",
                        "agreement-1",
                        "node-a",
                        "other-key",
                        "SHA-256",
                        "abcd",
                        "aGVsbG8=")))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  void getDoesNotPurgeOrphanFragments() {
    // Invariante: orphan fragments NUNCA se eliminan por TTL.
    final InMemoryRecoveryOrphanFragmentPayloadPort payloadPort =
        new InMemoryRecoveryOrphanFragmentPayloadPort();
    final TutorRecoveryService service = newService(clockAt("2026-03-06T18:00:00Z"), payloadPort);
    service.store(
        new TutorRecoveryService.StoreRecoveryFragmentRequest(
            "fragment-2",
            "agreement-2",
            "node-a",
            "allowed-key",
            "SHA-256",
            HELLO_SHA256,
            "aGVsbG8="));

    // Indefinido cuanto tiempo pase, la metadata + payload no se purgan.
    assertThat(service.get("fragment-2").fragmentId()).isEqualTo("fragment-2");
    assertThat(service.getContent("fragment-2")).isEqualTo("hello".getBytes());
    assertThat(payloadPort.findByFragmentId("fragment-2")).isPresent();
  }

  @Test
  void getThrowsForBlankFragmentId() {
    final TutorRecoveryService service = newService(clockAt("2026-03-06T18:00:00Z"));

    assertThatThrownBy(() -> service.get("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fragmentId must not be blank");
  }

  @Test
  void getThrowsForUnknownFragmentId() {
    final TutorRecoveryService service = newService(clockAt("2026-03-06T18:00:00Z"));

    assertThatThrownBy(() -> service.get("unknown"))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining("fragment not found");
  }

  @Test
  void storeRollsBackCustodyWhenPayloadSaveFails() {
    final InMemoryRecoveryOrphanFragmentPort custodyPort = new InMemoryRecoveryOrphanFragmentPort();
    final RecoveryObservabilityService observabilityService = new RecoveryObservabilityService();
    final RecoveryOrphanFragmentPayloadPort failingPayloadPort =
        new RecoveryOrphanFragmentPayloadPort() {
          @Override
          public void save(final String fragmentId, final byte[] payload) {
            throw new IllegalStateException("simulated payload failure");
          }

          @Override
          public java.util.Optional<byte[]> findByFragmentId(final String fragmentId) {
            return java.util.Optional.empty();
          }

          @Override
          public boolean exists(final String fragmentId) {
            return false;
          }

          @Override
          public void deleteByFragmentId(final String fragmentId) {
            // no-op test double
          }
        };

    final TutorRecoveryService service =
        new TutorRecoveryService(
            topologyWithAllowedKeys("allowed-key"),
            custodyPort,
            failingPayloadPort,
            new InMemoryRsCodecAdapter(),
            new InMemoryRsIntegrityVerifier(),
            clockAt("2026-03-06T18:00:00Z"),
            observabilityService);

    assertThatThrownBy(
            () ->
                service.store(
                    new TutorRecoveryService.StoreRecoveryFragmentRequest(
                        "fragment-failing-payload",
                        "agreement-1",
                        "node-a",
                        "allowed-key",
                        "SHA-256",
                        HELLO_SHA256,
                        "aGVsbG8=")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated payload failure");

    assertThat(custodyPort.findByFragmentId("fragment-failing-payload")).isEmpty();
    assertThat(observabilityService.snapshot().recoveryConsistencyCompensationTotal())
        .isEqualTo(1L);
  }

  @Test
  void reconstructsOriginalPayloadFromCustodiedFragments() {
    final byte[] originalPayload = "reed-solomon-recovery".getBytes(StandardCharsets.UTF_8);
    final String originalHash = sha256Hex(originalPayload);
    final RsScheme scheme = new RsScheme(6, 4, 8);
    final InMemoryRsCodecAdapter codec = new InMemoryRsCodecAdapter();
    final List<RsFragment> encodedFragments = codec.encode(originalPayload, scheme);

    final TutorRecoveryService service =
        newServiceWithCodec(clockAt("2026-03-06T18:00:00Z"), codec);

    final List<RsFragment> availableFragments =
        List.of(
            encodedFragments.get(0),
            encodedFragments.get(1),
            encodedFragments.get(3),
            encodedFragments.get(5));
    for (RsFragment fragment : availableFragments) {
      service.store(
          new TutorRecoveryService.StoreRecoveryFragmentRequest(
              fragment.fragmentId(),
              "agreement-rs",
              "node-a",
              "allowed-key",
              "SHA-256",
              fragment.checksum(),
              Base64.getEncoder().encodeToString(fragment.payload())));
    }

    final TutorRecoveryService.ReconstructedPayload reconstructed =
        service.reconstruct(
            new TutorRecoveryService.ReconstructRecoveryFragmentsRequest(
                "file-rs-1",
                originalHash,
                scheme.n(),
                scheme.k(),
                scheme.symbolSize(),
                availableFragments.stream()
                    .map(
                        fragment ->
                            new TutorRecoveryService.ReconstructFragmentReference(
                                fragment.fragmentId(), fragment.index(), fragment.isParity()))
                    .toList()));

    assertThat(reconstructed.fileId()).isEqualTo("file-rs-1");
    assertThat(reconstructed.checksum()).isEqualTo(originalHash);
    assertThat(reconstructed.payload()).isEqualTo(originalPayload);
  }

  @Test
  void reconstructionFailsWhenAvailableFragmentsAreLowerThanK() {
    final byte[] originalPayload = "reed-solomon-recovery".getBytes(StandardCharsets.UTF_8);
    final String originalHash = sha256Hex(originalPayload);
    final RsScheme scheme = new RsScheme(6, 4, 8);
    final InMemoryRsCodecAdapter codec = new InMemoryRsCodecAdapter();
    final List<RsFragment> encodedFragments = codec.encode(originalPayload, scheme);

    final TutorRecoveryService service =
        newServiceWithCodec(clockAt("2026-03-06T18:00:00Z"), codec);

    final List<RsFragment> insufficientFragments =
        List.of(encodedFragments.get(0), encodedFragments.get(2), encodedFragments.get(5));
    for (RsFragment fragment : insufficientFragments) {
      service.store(
          new TutorRecoveryService.StoreRecoveryFragmentRequest(
              fragment.fragmentId(),
              "agreement-rs",
              "node-a",
              "allowed-key",
              "SHA-256",
              fragment.checksum(),
              Base64.getEncoder().encodeToString(fragment.payload())));
    }

    assertThatThrownBy(
            () ->
                service.reconstruct(
                    new TutorRecoveryService.ReconstructRecoveryFragmentsRequest(
                        "file-rs-insufficient",
                        originalHash,
                        scheme.n(),
                        scheme.k(),
                        scheme.symbolSize(),
                        insufficientFragments.stream()
                            .map(
                                fragment ->
                                    new TutorRecoveryService.ReconstructFragmentReference(
                                        fragment.fragmentId(),
                                        fragment.index(),
                                        fragment.isParity()))
                            .toList())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least k fragment references are required");
  }

  private TutorRecoveryService newService(final Clock clock) {
    return newService(clock, new InMemoryRecoveryOrphanFragmentPayloadPort());
  }

  private TutorRecoveryService newService(
      final Clock clock, final RecoveryOrphanFragmentPayloadPort payloadPort) {
    return new TutorRecoveryService(
        topologyWithAllowedKeys("allowed-key"),
        new InMemoryRecoveryOrphanFragmentPort(),
        payloadPort,
        new InMemoryRsCodecAdapter(),
        new InMemoryRsIntegrityVerifier(),
        clock);
  }

  private TutorRecoveryService newServiceWithCodec(
      final Clock clock, final InMemoryRsCodecAdapter codec) {
    return new TutorRecoveryService(
        topologyWithAllowedKeys("allowed-key"),
        new InMemoryRecoveryOrphanFragmentPort(),
        new InMemoryRecoveryOrphanFragmentPayloadPort(),
        codec,
        new InMemoryRsIntegrityVerifier(),
        clock);
  }

  private NodeTopologyProperties topologyWithAllowedKeys(final String... keys) {
    final NodeTopologyProperties properties = new NodeTopologyProperties();
    properties.setTutorAcceptedPublicKeys(List.of(keys));
    return properties;
  }

  private Clock clockAt(final String iso) {
    return Clock.fixed(Instant.parse(iso), ZoneOffset.UTC);
  }

  private String sha256Hex(final byte[] payload) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] hash = digest.digest(payload);
      final StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte value : hash) {
        builder.append(String.format("%02x", value));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 algorithm is not available", ex);
    }
  }
}
