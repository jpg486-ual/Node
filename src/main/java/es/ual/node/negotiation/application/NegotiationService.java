package es.ual.node.negotiation.application;

import es.ual.node.identitysecurity.ports.out.PublicKeyRegistry;
import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.negotiation.domain.NegotiationAgreement;
import es.ual.node.negotiation.domain.NegotiationCreateRequest;
import es.ual.node.negotiation.domain.NegotiationStatus;
import es.ual.node.negotiation.domain.TransferAuthorizationToken;
import es.ual.node.negotiation.domain.TransferMode;
import es.ual.node.negotiation.ports.out.AgreementRepository;
import es.ual.node.negotiation.ports.out.CapacityPort;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Application service for negotiation and transfer authorization lifecycle. */
public class NegotiationService {

  private static final Logger LOGGER = LoggerFactory.getLogger(NegotiationService.class);
  private static final Pattern RS_PATTERN = Pattern.compile("(?i)RS\\((\\d+)\\s*,\\s*(\\d+)\\)");

  private final PublicKeyRegistry publicKeyRegistry;
  private final AgreementRepository agreementRepository;
  private final CapacityPort capacityPort;
  private final NegotiationProperties properties;
  private final Clock clock;

  /**
   * Creates negotiation service.
   *
   * @param publicKeyRegistry node registry
   * @param agreementRepository agreement repository
   * @param capacityPort node capacity port
   * @param properties negotiation configuration
   * @param clock clock
   */
  public NegotiationService(
      final PublicKeyRegistry publicKeyRegistry,
      final AgreementRepository agreementRepository,
      final CapacityPort capacityPort,
      final NegotiationProperties properties,
      final Clock clock) {
    if (publicKeyRegistry == null
        || agreementRepository == null
        || capacityPort == null
        || properties == null
        || clock == null) {
      throw new IllegalArgumentException("Dependencies must not be null");
    }
    this.publicKeyRegistry = publicKeyRegistry;
    this.agreementRepository = agreementRepository;
    this.capacityPort = capacityPort;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Creates a pending agreement after policy and capacity validations.
   *
   * @param request negotiation create request
   * @return created pending agreement
   */
  public NegotiationAgreement createAgreement(final NegotiationCreateRequest request) {
    final long plannedReservationBytes = validateCreateRequest(request);

    final Instant createdAt = clock.instant();
    final int expirationSeconds =
        request.expirationSeconds() > 0
            ? request.expirationSeconds()
            : properties.getDefaultAgreementExpiration();
    final Instant expiresAt = createdAt.plusSeconds(expirationSeconds);

    final NegotiationAgreement agreement =
        new NegotiationAgreement(
            UUID.randomUUID().toString(),
            request.requesterNodeId(),
            request.targetNodeId(),
            NegotiationStatus.PENDING,
            request.transferMode(),
            request.bucketSize(),
            request.expectedStorageBytes(),
            request.fragmentCount(),
            request.redundancyScheme(),
            plannedReservationBytes,
            request.fileManifest(),
            createdAt,
            expiresAt,
            request.requesterSignature(),
            null,
            null,
            request.requesterTutorNodeId(),
            request.requesterTutorBaseUrl());

    LOGGER
        .atInfo()
        .setMessage("Negotiation agreement created")
        .addKeyValue("agreementId", agreement.agreementId())
        .addKeyValue("requesterNodeId", agreement.requesterNodeId())
        .addKeyValue("targetNodeId", agreement.targetNodeId())
        .addKeyValue("transferMode", agreement.transferMode())
        .addKeyValue("expiresAt", agreement.expiresAt())
        .log();

    return agreementRepository.save(agreement);
  }

  /**
   * Confirms agreement and generates transfer authorization token.
   *
   * @param agreementId agreement id
   * @param targetSignature target signature
   * @return confirmed agreement
   */
  public NegotiationAgreement confirmAgreement(
      final String agreementId, final String targetSignature) {
    if (targetSignature == null || targetSignature.isBlank()) {
      throw new NegotiationException("targetSignature must not be blank");
    }

    final NegotiationAgreement agreement = getRequiredAgreement(agreementId);
    if (agreement.status() != NegotiationStatus.PENDING) {
      throw new NegotiationException("Only pending agreements can be confirmed");
    }
    if (agreement.isExpiredAt(clock.instant())) {
      agreementRepository.save(agreement.expire());
      throw new NegotiationException("Agreement is expired and cannot be confirmed");
    }

    boolean capacityReserved = false;
    try {
      capacityPort.reserve(agreement.agreementId(), agreement.plannedReservationBytes());
      capacityReserved = true;
      capacityPort.commit(agreement.agreementId());

      final TransferAuthorizationToken token =
          new TransferAuthorizationToken(
              UUID.randomUUID().toString(),
              agreement.agreementId(),
              clock.instant(),
              agreement.expiresAt());
      final NegotiationAgreement confirmed = agreement.confirm(targetSignature.trim(), token);
      final NegotiationAgreement persisted = agreementRepository.save(confirmed);

      LOGGER
          .atInfo()
          .setMessage("Negotiation agreement confirmed")
          .addKeyValue("agreementId", persisted.agreementId())
          .addKeyValue("token", persisted.transferAuthorizationToken().token())
          .log();

      return persisted;
    } catch (RuntimeException ex) {
      if (capacityReserved) {
        try {
          capacityPort.release(agreement.agreementId());
        } catch (RuntimeException releaseEx) {
          ex.addSuppressed(releaseEx);
        }
      }
      if (ex instanceof IllegalArgumentException) {
        throw new NegotiationException(ex.getMessage());
      }
      throw ex;
    }
  }

  /**
   * Rejects pending agreement.
   *
   * @param agreementId agreement id
   * @param reason rejection reason
   * @return rejected agreement
   */
  public NegotiationAgreement rejectAgreement(final String agreementId, final String reason) {
    if (reason == null || reason.isBlank()) {
      throw new NegotiationException("rejection reason must not be blank");
    }
    final NegotiationAgreement agreement = getRequiredAgreement(agreementId);
    if (agreement.status() != NegotiationStatus.PENDING) {
      throw new NegotiationException("Only pending agreements can be rejected");
    }
    return agreementRepository.save(agreement.reject());
  }

  /**
   * Cancels an agreement. Allowed from PENDING (legacy "give up before commit") or from CONFIRMED
   * Already-terminal states (REJECTED, CANCELLED, EXPIRED) reject with {@link NegotiationException}
   * to avoid undefined transitions.
   *
   * @param agreementId agreement id
   * @return cancelled agreement
   */
  public NegotiationAgreement cancelAgreement(final String agreementId) {
    final NegotiationAgreement agreement = getRequiredAgreement(agreementId);
    if (agreement.status() != NegotiationStatus.PENDING
        && agreement.status() != NegotiationStatus.CONFIRMED) {
      throw new NegotiationException(
          "Only PENDING or CONFIRMED agreements can be cancelled (was " + agreement.status() + ")");
    }
    return agreementRepository.save(agreement.cancel());
  }

  /**
   * Retrieves agreement by id.
   *
   * @param agreementId agreement id
   * @return agreement
   */
  public NegotiationAgreement getAgreement(final String agreementId) {
    return getRequiredAgreement(agreementId);
  }

  private NegotiationAgreement getRequiredAgreement(final String agreementId) {
    return agreementRepository
        .findById(agreementId)
        .orElseThrow(() -> new NegotiationException("Agreement not found"));
  }

  private long validateCreateRequest(final NegotiationCreateRequest request) {
    if (request == null) {
      throw new NegotiationException("Negotiation request must not be null");
    }
    if (!publicKeyRegistry.isRegistered(request.requesterNodeId())) {
      throw new NegotiationException("Requester node is not registered");
    }
    if (!publicKeyRegistry.isRegistered(request.targetNodeId())) {
      throw new NegotiationException("Target node is not registered");
    }
    if (agreementRepository.countPending() >= properties.getMaxConcurrentNegotiations()) {
      throw new NegotiationException("Max concurrent negotiations reached");
    }

    final long worstCaseBytes =
        calculateWorstCaseReservation(
            request.expectedStorageBytes(),
            request.redundancyScheme(),
            properties.getBucketMaxRatio());

    if (!capacityPort.canReserve(worstCaseBytes)) {
      throw new NegotiationException("Insufficient node capacity");
    }

    validateManifest(request.transferMode(), request.fileManifest());
    validateQueuedFragments(request.transferMode(), request.bucketSize());

    return worstCaseBytes;
  }

  private void validateManifest(final TransferMode transferMode, final FileManifest manifest) {
    final boolean requiresManifest = transferMode == TransferMode.MANIFEST_ONLY;

    if (requiresManifest && manifest == null) {
      throw new NegotiationException("fileManifest is required for selected transferMode");
    }

    if (manifest == null) {
      return;
    }

    if (manifest.originalFileHash() == null || manifest.originalFileHash().length() != 64) {
      throw new NegotiationException("Invalid manifest hash");
    }
  }

  private void validateQueuedFragments(final TransferMode transferMode, final long bucketSize) {
    // No-op: la cola `queued_fragment` no se alimenta en el modelo cerrado actual
    // (vestigio del modelo simétrico abierto). Lectura suprimida, siempre devolvía
    // false y bloqueaba toda negociación FRAGMENTS_ONLY. Se mantiene la signatura para
    // que la activación de un futuro path simétrico pueda reincorporar la validación
    // sin tocar callers.
  }

  private long calculateWorstCaseReservation(
      final long expectedStorageBytes, final String redundancyScheme, final double bucketMaxRatio) {
    if (bucketMaxRatio < 1.0d) {
      throw new NegotiationException("bucketMaxRatio must be greater than or equal to 1.0");
    }

    final double rsFactor = parseRedundancyFactor(redundancyScheme);
    final double value = expectedStorageBytes * bucketMaxRatio * rsFactor;
    return (long) Math.ceil(value);
  }

  private double parseRedundancyFactor(final String redundancyScheme) {
    if (redundancyScheme == null || redundancyScheme.isBlank()) {
      return 1.0d;
    }

    final Matcher matcher = RS_PATTERN.matcher(redundancyScheme.trim());
    if (!matcher.matches()) {
      throw new NegotiationException("Invalid redundancyScheme format, expected RS(n,k)");
    }

    final int n = Integer.parseInt(matcher.group(1));
    final int k = Integer.parseInt(matcher.group(2));
    if (n <= 0 || k <= 0 || n < k) {
      throw new NegotiationException("Invalid redundancyScheme values");
    }

    return ((double) n) / k;
  }
}
