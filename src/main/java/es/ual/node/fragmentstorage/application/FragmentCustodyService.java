package es.ual.node.fragmentstorage.application;

import es.ual.node.fragmentstorage.domain.CustodyFragment;
import es.ual.node.fragmentstorage.domain.CustodyInventoryItem;
import es.ual.node.fragmentstorage.ports.out.CapacityCheckPort;
import es.ual.node.fragmentstorage.ports.out.CustodyEventNotifierPort;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPayloadPort;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPort;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * General-purpose fragment custody service.
 *
 * <p>Receives fragments from authorized network peers and persists them via the storage ports
 * already shared with {@code TutorRecoveryService}. Validation is independent from tutor
 * escalation: the whitelist used here is {@code acceptedFragmentSenderKeys} (any node authorized to
 * send fragments to this custodian), distinct from {@code tutorAcceptedPublicKeys} (nodes
 * authorized to escalate orphans to this tutor specifically).
 *
 * <p>Conceptually:
 *
 * <ul>
 *   <li>{@code FragmentCustodyService} = "I am a custodian; accept fragments from trusted peers".
 *   <li>{@code TutorRecoveryService} = "I am a tutor; accept escalations + reconstruct + serve
 *       proactive manifests".
 * </ul>
 *
 * <p>Both write to the same physical storage (`recovery_fragment` + `recovery_fragment_payload`)
 * because the bytes-and-metadata representation is identical.
 */
public class FragmentCustodyService {

  private static final Logger LOGGER = LoggerFactory.getLogger(FragmentCustodyService.class);

  private final CustodyFragmentPort custodyPort;
  private final CustodyFragmentPayloadPort payloadPort;
  private final CapacityCheckPort capacityCheckPort;
  private final CustodyEventNotifierPort eventNotifier;
  private final Set<String> acceptedSenderKeys;
  private final long defaultCustodySeconds;
  private final Clock clock;

  /** Creates service. */
  public FragmentCustodyService(
      final CustodyFragmentPort custodyPort,
      final CustodyFragmentPayloadPort payloadPort,
      final CapacityCheckPort capacityCheckPort,
      final CustodyEventNotifierPort eventNotifier,
      final List<String> acceptedSenderKeys,
      final long defaultCustodySeconds,
      final Clock clock) {
    if (custodyPort == null
        || payloadPort == null
        || capacityCheckPort == null
        || eventNotifier == null
        || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    if (defaultCustodySeconds <= 0) {
      throw new IllegalArgumentException("defaultCustodySeconds must be greater than zero");
    }
    this.custodyPort = custodyPort;
    this.payloadPort = payloadPort;
    this.capacityCheckPort = capacityCheckPort;
    this.eventNotifier = eventNotifier;
    this.acceptedSenderKeys =
        Set.copyOf(acceptedSenderKeys == null ? List.of() : acceptedSenderKeys);
    this.defaultCustodySeconds = defaultCustodySeconds;
    this.clock = clock;
  }

  /**
   * Stores a fragment received from an authorized network peer.
   *
   * @param request store request
   * @return stored metadata
   * @throws FragmentCustodyAuthorizationException when the sender public key is not whitelisted
   * @throws IllegalArgumentException when validation fails (checksum mismatch, blank fields, etc)
   */
  public CustodyFragment store(final StoreFragmentRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (!acceptedSenderKeys.contains(request.senderPublicKey())) {
      throw new FragmentCustodyAuthorizationException(
          "sender public key is not in acceptedFragmentSenderKeys whitelist");
    }

    final byte[] payload = request.payload();
    if (payload == null || payload.length == 0) {
      throw new IllegalArgumentException("payload must not be empty");
    }
    if (!capacityCheckPort.canAccept(payload.length)) {
      LOGGER
          .atWarn()
          .setMessage("Custody store rejected: insufficient storage on this node")
          .addKeyValue("event", "CUSTODY_INSUFFICIENT_STORAGE")
          .addKeyValue("severity", "high")
          .addKeyValue("fragmentId", request.fragmentId())
          .addKeyValue("senderNodeId", request.senderNodeId())
          .addKeyValue("requestedBytes", payload.length)
          .log();
      throw new CustodyInsufficientStorageException(
          "node has no capacity to accept fragment of " + payload.length + " bytes");
    }
    final String computedChecksum = computeChecksumHex(request.checksumAlgorithm(), payload);
    if (!computedChecksum.equalsIgnoreCase(request.checksum())) {
      throw new IllegalArgumentException("checksum does not match payload");
    }

    final long custodySeconds =
        request.custodySeconds() == null ? defaultCustodySeconds : request.custodySeconds();
    if (custodySeconds <= 0) {
      throw new IllegalArgumentException("custodySeconds must be greater than zero");
    }

    final Instant now = Instant.now(clock);
    final Instant expiresAt = now.plusSeconds(custodySeconds);
    final CustodyFragment stored =
        new CustodyFragment(
            request.fragmentId(),
            request.agreementId(),
            request.senderNodeId(),
            request.checksumAlgorithm(),
            request.checksum(),
            payload.length,
            now,
            expiresAt);
    custodyPort.save(stored);
    try {
      payloadPort.save(stored.fragmentId(), payload);
    } catch (RuntimeException exception) {
      try {
        custodyPort.deleteByFragmentId(stored.fragmentId());
      } catch (RuntimeException rollbackException) {
        exception.addSuppressed(rollbackException);
      }
      throw exception;
    }

    LOGGER
        .atInfo()
        .setMessage("Fragment custody store accepted")
        .addKeyValue("fragmentId", stored.fragmentId())
        .addKeyValue("agreementId", stored.agreementId())
        .addKeyValue("senderNodeId", stored.requesterNodeId())
        .addKeyValue("sizeBytes", stored.sizeBytes())
        .log();

    // Notify after-store hook so custody-liveness (when enabled) can auto-bootstrap an
    // outbound probe session against the origin. Defensive try/catch, the contract of
    // CustodyEventNotifierPort says implementations must not propagate failures, but the custody
    // store must remain durable even if a malformed adapter does.
    try {
      eventNotifier.onCustodyFragmentStored(stored.requesterNodeId());
    } catch (RuntimeException notifierFailure) {
      LOGGER
          .atWarn()
          .setMessage("Custody event notifier failed; store remains durable")
          .addKeyValue("event", "CUSTODY_EVENT_NOTIFIER_FAILED")
          .addKeyValue("fragmentId", stored.fragmentId())
          .addKeyValue("requesterNodeId", stored.requesterNodeId())
          .addKeyValue("error", notifierFailure.getMessage())
          .log();
    }

    return stored;
  }

  /**
   * Reads fragment bytes for a download request from the origin node.
   *
   * @param fragmentId fragment identifier
   * @return raw bytes
   * @throws NoSuchElementException when the fragment is not held by this custodian
   */
  public byte[] findContent(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    return payloadPort
        .findByFragmentId(fragmentId.trim())
        .orElseThrow(() -> new NoSuchElementException("fragment not found: " + fragmentId));
  }

  /**
   * Reads fragment metadata.
   *
   * @param fragmentId fragment identifier
   * @return stored metadata
   * @throws NoSuchElementException when the fragment is not held by this custodian
   */
  public CustodyFragment findMetadata(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    return custodyPort
        .findByFragmentId(fragmentId.trim())
        .orElseThrow(() -> new NoSuchElementException("fragment not found: " + fragmentId));
  }

  /**
   * Returns active custody fragments held by this node (moved from {@code TutorRecoveryService}
   * after the recovery↔custody domain split). Filters out expired rows in memory.
   *
   * @return active custody fragments
   */
  public List<CustodyFragment> findAllActiveCustodies() {
    final Instant now = Instant.now(clock);
    return custodyPort.findAll().stream()
        .filter(stored -> !stored.expiresAt().isBefore(now))
        .toList();
  }

  /**
   * Returns the inventory of custodied fragments owned by {@code requesterNodeId} (the origin),
   * with TTL-remaining computed against the current clock. Used by the inventory pull endpoint
   * {@code GET /custody/fragments/by-requester/{nodeId}}.
   *
   * <p>Only fragments still alive (not yet expired) are returned. The pulling origin uses {@code
   * ttlRemainingSeconds} to detect at-risk placements that need pre-emptive redistribution during
   * recovery.
   *
   * @param requesterNodeId origin's node identifier
   * @return inventory items (empty when nothing is custodied for that origin)
   */
  public List<CustodyInventoryItem> listInventoryByRequester(final String requesterNodeId) {
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      throw new IllegalArgumentException("requesterNodeId must not be blank");
    }
    final Instant now = Instant.now(clock);
    return custodyPort.findByRequesterNodeId(requesterNodeId.trim()).stream()
        .filter(stored -> !stored.expiresAt().isBefore(now))
        .map(
            stored ->
                new CustodyInventoryItem(
                    stored.fragmentId(),
                    stored.agreementId(),
                    stored.sizeBytes(),
                    stored.checksum(),
                    stored.expiresAt(),
                    Math.max(0L, stored.expiresAt().getEpochSecond() - now.getEpochSecond())))
        .toList();
  }

  /**
   * Extends the custody window of a live custody fragment by the given number of seconds (moved
   * from {@code TutorRecoveryService} after the domain split).
   *
   * @param fragmentId fragment id
   * @param additionalSeconds seconds to add to current {@code expiresAt}
   * @return updated custody fragment metadata
   * @throws NoSuchElementException when the fragment is unknown
   * @throws IllegalStateException when the fragment is already expired
   */
  public CustodyFragment extendCustody(final String fragmentId, final long additionalSeconds) {
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    if (additionalSeconds <= 0) {
      throw new IllegalArgumentException("additionalSeconds must be greater than zero");
    }
    final String normalized = fragmentId.trim();
    final CustodyFragment existing =
        custodyPort
            .findByFragmentId(normalized)
            .orElseThrow(() -> new NoSuchElementException("fragment not found: " + normalized));
    if (existing.expiresAt().isBefore(Instant.now(clock))) {
      throw new IllegalStateException("fragment is already expired: " + normalized);
    }
    final CustodyFragment extended =
        new CustodyFragment(
            existing.fragmentId(),
            existing.agreementId(),
            existing.requesterNodeId(),
            existing.checksumAlgorithm(),
            existing.checksum(),
            existing.sizeBytes(),
            existing.storedAt(),
            existing.expiresAt().plusSeconds(additionalSeconds));
    custodyPort.save(extended);
    LOGGER
        .atInfo()
        .setMessage("Custody extended")
        .addKeyValue("event", "CUSTODY_EXTENDED")
        .addKeyValue("fragmentId", normalized)
        .addKeyValue("additionalSeconds", additionalSeconds)
        .addKeyValue("newExpiresAt", extended.expiresAt().toString())
        .log();
    return extended;
  }

  private static String computeChecksumHex(final String algorithm, final byte[] payload) {
    try {
      final MessageDigest digest = MessageDigest.getInstance(algorithm);
      return HexFormat.of().formatHex(digest.digest(payload));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalArgumentException("checksum algorithm not supported: " + algorithm);
    }
  }

  /** Request payload for {@link #store(StoreFragmentRequest)}. */
  public record StoreFragmentRequest(
      String fragmentId,
      String agreementId,
      String senderNodeId,
      String senderPublicKey,
      String checksumAlgorithm,
      String checksum,
      byte[] payload,
      Long custodySeconds) {

    public StoreFragmentRequest {
      if (fragmentId == null || fragmentId.isBlank()) {
        throw new IllegalArgumentException("fragmentId must not be blank");
      }
      if (agreementId == null || agreementId.isBlank()) {
        throw new IllegalArgumentException("agreementId must not be blank");
      }
      if (senderNodeId == null || senderNodeId.isBlank()) {
        throw new IllegalArgumentException("senderNodeId must not be blank");
      }
      if (senderPublicKey == null || senderPublicKey.isBlank()) {
        throw new IllegalArgumentException("senderPublicKey must not be blank");
      }
      if (checksumAlgorithm == null || checksumAlgorithm.isBlank()) {
        throw new IllegalArgumentException("checksumAlgorithm must not be blank");
      }
      if (checksum == null || checksum.isBlank()) {
        throw new IllegalArgumentException("checksum must not be blank");
      }
      fragmentId = fragmentId.trim();
      agreementId = agreementId.trim();
      senderNodeId = senderNodeId.trim();
      senderPublicKey = senderPublicKey.trim();
      checksumAlgorithm = checksumAlgorithm.trim();
      checksum = checksum.trim().toLowerCase();
    }
  }
}
