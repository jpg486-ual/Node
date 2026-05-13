package es.ual.node.recovery.application;

import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import es.ual.node.recovery.domain.RecoveryOrphanFragment;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPayloadPort;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPort;
import es.ual.node.reedsolomon.domain.RsFragment;
import es.ual.node.reedsolomon.domain.RsScheme;
import es.ual.node.reedsolomon.ports.out.RsDecoderPort;
import es.ual.node.reedsolomon.ports.out.RsIntegrityVerifierPort;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/** Handles tutor custody for recovery orphan fragments. */
public class TutorRecoveryService {

  private static final Logger LOGGER = LoggerFactory.getLogger(TutorRecoveryService.class);

  private final Clock clock;
  private final Set<String> allowedPublicKeys;
  private final RecoveryOrphanFragmentPort recoveryOrphanFragmentPort;
  private final RecoveryOrphanFragmentPayloadPort recoveryOrphanFragmentPayloadPort;
  private final RsDecoderPort rsDecoderPort;
  private final RsIntegrityVerifierPort rsIntegrityVerifierPort;
  private final RecoveryObservabilityService recoveryObservabilityService;
  private final ObservationRegistry observationRegistry;

  /**
   * Creates service.
   *
   * @param topologyProperties topology properties with tutor whitelist
   * @param recoveryOrphanFragmentPort persistence port
   * @param recoveryOrphanFragmentPayloadPort payload storage port
   * @param rsDecoderPort RS decoder port
   * @param rsIntegrityVerifierPort RS integrity verifier port
   * @param clock clock bean
   */
  public TutorRecoveryService(
      final NodeTopologyProperties topologyProperties,
      final RecoveryOrphanFragmentPort recoveryOrphanFragmentPort,
      final RecoveryOrphanFragmentPayloadPort recoveryOrphanFragmentPayloadPort,
      final RsDecoderPort rsDecoderPort,
      final RsIntegrityVerifierPort rsIntegrityVerifierPort,
      final Clock clock) {
    this(
        topologyProperties,
        recoveryOrphanFragmentPort,
        recoveryOrphanFragmentPayloadPort,
        rsDecoderPort,
        rsIntegrityVerifierPort,
        clock,
        RecoveryObservabilityService.noop());
  }

  /**
   * Creates service with observability tracker.
   *
   * @param topologyProperties topology properties with tutor whitelist
   * @param recoveryOrphanFragmentPort persistence port
   * @param recoveryOrphanFragmentPayloadPort payload storage port
   * @param rsDecoderPort RS decoder port
   * @param rsIntegrityVerifierPort RS integrity verifier port
   * @param clock clock bean
   * @param recoveryObservabilityService recovery observability tracker
   */
  public TutorRecoveryService(
      final NodeTopologyProperties topologyProperties,
      final RecoveryOrphanFragmentPort recoveryOrphanFragmentPort,
      final RecoveryOrphanFragmentPayloadPort recoveryOrphanFragmentPayloadPort,
      final RsDecoderPort rsDecoderPort,
      final RsIntegrityVerifierPort rsIntegrityVerifierPort,
      final Clock clock,
      final RecoveryObservabilityService recoveryObservabilityService) {
    this(
        topologyProperties,
        recoveryOrphanFragmentPort,
        recoveryOrphanFragmentPayloadPort,
        rsDecoderPort,
        rsIntegrityVerifierPort,
        clock,
        recoveryObservabilityService,
        ObservationRegistry.NOOP);
  }

  /**
   * Creates service with observability tracker and observation registry for domain spans.
   *
   * @param topologyProperties topology properties with tutor whitelist
   * @param recoveryOrphanFragmentPort persistence port
   * @param recoveryOrphanFragmentPayloadPort payload storage port
   * @param rsDecoderPort RS decoder port
   * @param rsIntegrityVerifierPort RS integrity verifier port
   * @param clock clock bean
   * @param recoveryObservabilityService recovery observability tracker
   * @param observationRegistry observation registry for domain spans
   */
  public TutorRecoveryService(
      final NodeTopologyProperties topologyProperties,
      final RecoveryOrphanFragmentPort recoveryOrphanFragmentPort,
      final RecoveryOrphanFragmentPayloadPort recoveryOrphanFragmentPayloadPort,
      final RsDecoderPort rsDecoderPort,
      final RsIntegrityVerifierPort rsIntegrityVerifierPort,
      final Clock clock,
      final RecoveryObservabilityService recoveryObservabilityService,
      final ObservationRegistry observationRegistry) {
    if (topologyProperties == null
        || recoveryOrphanFragmentPort == null
        || recoveryOrphanFragmentPayloadPort == null
        || rsDecoderPort == null
        || rsIntegrityVerifierPort == null
        || clock == null
        || recoveryObservabilityService == null
        || observationRegistry == null) {
      throw new IllegalArgumentException("Dependencies must not be null");
    }
    this.clock = clock;
    this.recoveryOrphanFragmentPort = recoveryOrphanFragmentPort;
    this.recoveryOrphanFragmentPayloadPort = recoveryOrphanFragmentPayloadPort;
    this.rsDecoderPort = rsDecoderPort;
    this.rsIntegrityVerifierPort = rsIntegrityVerifierPort;
    this.recoveryObservabilityService = recoveryObservabilityService;
    this.observationRegistry = observationRegistry;
    this.allowedPublicKeys =
        topologyProperties.getTutorAcceptedPublicKeys().stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /**
   * Stores a fragment under tutor custody when requester key is whitelisted.
   *
   * @param request store request
   * @return stored metadata
   */
  @Transactional
  public RecoveryOrphanFragment store(final StoreRecoveryFragmentRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (!allowedPublicKeys.contains(request.requesterPublicKey())) {
      throw new SecurityException("requester public key is not accepted by tutor");
    }

    final byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(request.payloadBase64());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("payloadBase64 must be valid Base64", ex);
    }
    if (bytes.length == 0) {
      throw new IllegalArgumentException("payloadBase64 must decode to non-empty bytes");
    }
    final String computedChecksum = computeChecksumHex(request.checksumAlgorithm(), bytes);
    if (!computedChecksum.equalsIgnoreCase(request.checksum())) {
      throw new IllegalArgumentException("checksum does not match payload");
    }

    final Instant now = Instant.now(clock);

    final RecoveryOrphanFragment stored =
        new RecoveryOrphanFragment(
            request.fragmentId(),
            request.agreementId(),
            request.requesterNodeId(),
            request.checksumAlgorithm(),
            request.checksum(),
            bytes.length,
            now);
    recoveryOrphanFragmentPort.save(stored);
    try {
      recoveryOrphanFragmentPayloadPort.save(stored.fragmentId(), bytes);
    } catch (RuntimeException exception) {
      try {
        recoveryOrphanFragmentPort.deleteByFragmentId(stored.fragmentId());
        recoveryObservabilityService.onStoreCompensated();
        LOGGER
            .atWarn()
            .setMessage("Recovery store compensated after payload failure")
            .addKeyValue("event", "STORE_COMPENSATED")
            .addKeyValue("result", "success")
            .addKeyValue("fragmentId", stored.fragmentId())
            .addKeyValue("agreementId", stored.agreementId())
            .addKeyValue("error", exception.getMessage())
            .log();
      } catch (RuntimeException rollbackException) {
        exception.addSuppressed(rollbackException);
        LOGGER
            .atError()
            .setMessage("Recovery store compensation failed")
            .addKeyValue("event", "STORE_COMPENSATED")
            .addKeyValue("result", "error")
            .addKeyValue("fragmentId", stored.fragmentId())
            .addKeyValue("agreementId", stored.agreementId())
            .addKeyValue("error", rollbackException.getMessage())
            .log();
      }
      throw exception;
    }
    return stored;
  }

  /**
   * Loads stored fragment metadata.
   *
   * @param fragmentId fragment id
   * @return stored fragment metadata
   */
  @Transactional(readOnly = true)
  public RecoveryOrphanFragment get(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    return recoveryOrphanFragmentPort
        .findByFragmentId(fragmentId.trim())
        .orElseThrow(() -> new NoSuchElementException("fragment not found"));
  }

  /**
   * Loads stored fragment payload bytes.
   *
   * @param fragmentId fragment id
   * @return payload bytes
   */
  public byte[] getContent(final String fragmentId) {
    final RecoveryOrphanFragment stored = get(fragmentId);
    return recoveryOrphanFragmentPayloadPort
        .findByFragmentId(stored.fragmentId())
        .orElseThrow(() -> new NoSuchElementException("fragment payload not found"));
  }

  /** Returns all tutor-custodied orphan fragments ordered by latest storage timestamp. */
  public List<RecoveryOrphanFragment> findAllOrphans() {
    return recoveryOrphanFragmentPort.findAll();
  }

  /**
   * Reconstructs original payload from currently custodied RS fragments.
   *
   * @param request reconstruction request
   * @return reconstructed payload
   */
  public ReconstructedPayload reconstruct(final ReconstructRecoveryFragmentsRequest request) {
    return Observation.createNotStarted("node.recovery.reconstruct", observationRegistry)
        .lowCardinalityKeyValue(
            "fragment.count",
            request != null && request.fragments() != null
                ? String.valueOf(request.fragments().size())
                : "0")
        .observe(() -> doReconstruct(request));
  }

  private ReconstructedPayload doReconstruct(final ReconstructRecoveryFragmentsRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }

    final RsScheme scheme =
        new RsScheme(request.redundancyN(), request.redundancyK(), request.symbolSize());
    if (request.fragments().size() < scheme.k()) {
      throw new IllegalArgumentException(
          "at least k fragment references are required for reconstruction");
    }

    // Tolerate missing fragments. After RETURN_TO_TUTOR the tutor only holds the
    // fragments that escaped peers escalated; fragments self-custodied by the origin (lost when
    // the origin's storage was destroyed) are absent here. If the survivors are still ≥ k, RS can
    // reconstruct. Below k → unrecoverable, surface a clear error instead of a generic 404.
    final List<RsFragment> fragments =
        request.fragments().stream()
            .map(
                reference -> {
                  final RecoveryOrphanFragment stored;
                  try {
                    stored = get(reference.fragmentId());
                  } catch (NoSuchElementException missing) {
                    return (RsFragment) null;
                  }
                  if (!"SHA-256".equalsIgnoreCase(stored.checksumAlgorithm())) {
                    throw new IllegalArgumentException(
                        "stored fragment checksumAlgorithm must be SHA-256");
                  }
                  final byte[] payload = getContent(reference.fragmentId());
                  return new RsFragment(
                      stored.fragmentId(),
                      reference.index(),
                      reference.parity(),
                      stored.checksum(),
                      payload.length,
                      payload);
                })
            .filter(java.util.Objects::nonNull)
            .toList();
    if (fragments.size() < scheme.k()) {
      throw new IllegalArgumentException(
          "only "
              + fragments.size()
              + " of "
              + request.fragments().size()
              + " referenced fragments are available in tutor; need at least k="
              + scheme.k()
              + " to reconstruct");
    }

    final byte[] reconstructed =
        Observation.createNotStarted("node.reedsolomon.decode", observationRegistry)
            .lowCardinalityKeyValue("scheme.n", String.valueOf(scheme.n()))
            .lowCardinalityKeyValue("scheme.k", String.valueOf(scheme.k()))
            .lowCardinalityKeyValue("available.shards", String.valueOf(fragments.size()))
            .observe(() -> rsDecoderPort.reconstruct(fragments, scheme));
    if (!rsIntegrityVerifierPort.verify(reconstructed, request.expectedOriginalHash())) {
      throw new IllegalArgumentException("reconstructed payload integrity validation failed");
    }

    return new ReconstructedPayload(
        request.fileId(), "SHA-256", computeChecksumHex("SHA-256", reconstructed), reconstructed);
  }

  /** Request object for storing a recovery fragment. */
  public record StoreRecoveryFragmentRequest(
      String fragmentId,
      String agreementId,
      String requesterNodeId,
      String requesterPublicKey,
      String checksumAlgorithm,
      String checksum,
      String payloadBase64) {
    /** Creates validated request. */
    public StoreRecoveryFragmentRequest {
      if (fragmentId == null || fragmentId.isBlank()) {
        throw new IllegalArgumentException("fragmentId must not be blank");
      }
      if (agreementId == null || agreementId.isBlank()) {
        throw new IllegalArgumentException("agreementId must not be blank");
      }
      if (requesterNodeId == null || requesterNodeId.isBlank()) {
        throw new IllegalArgumentException("requesterNodeId must not be blank");
      }
      if (requesterPublicKey == null || requesterPublicKey.isBlank()) {
        throw new IllegalArgumentException("requesterPublicKey must not be blank");
      }
      if (checksumAlgorithm == null || checksumAlgorithm.isBlank()) {
        throw new IllegalArgumentException("checksumAlgorithm must not be blank");
      }
      if (checksum == null || checksum.isBlank()) {
        throw new IllegalArgumentException("checksum must not be blank");
      }
      if (payloadBase64 == null || payloadBase64.isBlank()) {
        throw new IllegalArgumentException("payloadBase64 must not be blank");
      }

      fragmentId = fragmentId.trim();
      agreementId = agreementId.trim();
      requesterNodeId = requesterNodeId.trim();
      requesterPublicKey = requesterPublicKey.trim();
      checksumAlgorithm = checksumAlgorithm.trim();
      checksum = checksum.trim();
      payloadBase64 = payloadBase64.trim();
    }
  }

  /** Request object for RS reconstruction from custodied fragments. */
  public record ReconstructRecoveryFragmentsRequest(
      String fileId,
      String expectedOriginalHash,
      int redundancyN,
      int redundancyK,
      int symbolSize,
      List<ReconstructFragmentReference> fragments) {
    /** Creates validated reconstruction request. */
    public ReconstructRecoveryFragmentsRequest {
      if (fileId == null || fileId.isBlank()) {
        throw new IllegalArgumentException("fileId must not be blank");
      }
      if (expectedOriginalHash == null || expectedOriginalHash.isBlank()) {
        throw new IllegalArgumentException("expectedOriginalHash must not be blank");
      }
      if (redundancyN <= 0 || redundancyK <= 0 || redundancyN < redundancyK) {
        throw new IllegalArgumentException("invalid redundancy values");
      }
      if (symbolSize <= 0) {
        throw new IllegalArgumentException("symbolSize must be greater than zero");
      }
      if (fragments == null || fragments.isEmpty()) {
        throw new IllegalArgumentException("fragments must not be empty");
      }
      if (fragments.stream().anyMatch(fragment -> fragment == null)) {
        throw new IllegalArgumentException("fragments must not contain null values");
      }

      fileId = fileId.trim();
      expectedOriginalHash = expectedOriginalHash.trim().toLowerCase(Locale.ROOT);
      fragments = List.copyOf(fragments);
    }
  }

  /** Fragment reference used by RS reconstruction. */
  public record ReconstructFragmentReference(String fragmentId, int index, boolean parity) {
    /** Creates validated fragment reference. */
    public ReconstructFragmentReference {
      if (fragmentId == null || fragmentId.isBlank()) {
        throw new IllegalArgumentException("fragmentId must not be blank");
      }
      if (index < 0) {
        throw new IllegalArgumentException("index must not be negative");
      }
      fragmentId = fragmentId.trim();
    }
  }

  /** Reconstructed payload output. */
  public record ReconstructedPayload(
      String fileId, String checksumAlgorithm, String checksum, byte[] payload) {
    /** Creates immutable reconstructed payload. */
    public ReconstructedPayload {
      if (fileId == null || fileId.isBlank()) {
        throw new IllegalArgumentException("fileId must not be blank");
      }
      if (checksumAlgorithm == null || checksumAlgorithm.isBlank()) {
        throw new IllegalArgumentException("checksumAlgorithm must not be blank");
      }
      if (checksum == null || checksum.isBlank()) {
        throw new IllegalArgumentException("checksum must not be blank");
      }
      if (payload == null || payload.length == 0) {
        throw new IllegalArgumentException("payload must not be empty");
      }

      fileId = fileId.trim();
      checksumAlgorithm = checksumAlgorithm.trim();
      checksum = checksum.trim().toLowerCase(Locale.ROOT);
      payload = payload.clone();
    }

    /**
     * Returns defensive payload copy.
     *
     * @return payload bytes
     */
    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }

  private static String computeChecksumHex(final String algorithm, final byte[] payload) {
    try {
      final MessageDigest digest = MessageDigest.getInstance(algorithm);
      final byte[] hash = digest.digest(payload);
      final StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte value : hash) {
        builder.append(String.format("%02x", value));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalArgumentException("Unsupported checksumAlgorithm: " + algorithm, ex);
    }
  }
}
