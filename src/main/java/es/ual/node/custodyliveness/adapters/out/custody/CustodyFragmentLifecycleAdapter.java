package es.ual.node.custodyliveness.adapters.out.custody;

import es.ual.node.custodyliveness.ports.out.CustodyFragmentLifecyclePort;
import es.ual.node.fragmentstorage.domain.CustodyFragment;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPayloadPort;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPort;
import es.ual.node.negotiation.application.NegotiationException;
import es.ual.node.negotiation.application.NegotiationService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter implementing {@link CustodyFragmentLifecyclePort} on top of the
 * <strong>custody-domain</strong> ports. Used by {@code custodyliveness} to extend or release
 * CUSTODY fragments, recovery_orphan fragments at the tutor are out of scope for the probe cycle.
 *
 * <p>{@link #decommissionCustody(String)} drops the fragment AND cancels the backing agreement via
 * {@link NegotiationService} so the probe-extension flow is fully idempotent and the agreement
 * state machine reflects the eviction.
 */
public class CustodyFragmentLifecycleAdapter implements CustodyFragmentLifecyclePort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(CustodyFragmentLifecycleAdapter.class);

  private final CustodyFragmentPort custodyFragmentPort;
  private final CustodyFragmentPayloadPort custodyFragmentPayloadPort;
  private final NegotiationService negotiationService;

  /** Legacy 2-arg constructor: wirings without agreement cancellation. */
  public CustodyFragmentLifecycleAdapter(
      final CustodyFragmentPort custodyFragmentPort,
      final CustodyFragmentPayloadPort custodyFragmentPayloadPort) {
    this(custodyFragmentPort, custodyFragmentPayloadPort, null);
  }

  /** Full constructor: wires NegotiationService for agreement cancellation. */
  public CustodyFragmentLifecycleAdapter(
      final CustodyFragmentPort custodyFragmentPort,
      final CustodyFragmentPayloadPort custodyFragmentPayloadPort,
      final NegotiationService negotiationService) {
    if (custodyFragmentPort == null || custodyFragmentPayloadPort == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.custodyFragmentPort = custodyFragmentPort;
    this.custodyFragmentPayloadPort = custodyFragmentPayloadPort;
    this.negotiationService = negotiationService;
  }

  @Override
  public Optional<CustodyFragment> findByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return Optional.empty();
    }
    return custodyFragmentPort.findByFragmentId(fragmentId.trim());
  }

  @Override
  public void extendCustody(final String fragmentId, final long additionalSeconds) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return;
    }
    if (additionalSeconds <= 0) {
      throw new IllegalArgumentException("additionalSeconds must be greater than zero");
    }
    final String normalized = fragmentId.trim();
    final Optional<CustodyFragment> existing = custodyFragmentPort.findByFragmentId(normalized);
    if (existing.isEmpty()) {
      return;
    }
    final CustodyFragment current = existing.get();
    final CustodyFragment extended =
        new CustodyFragment(
            current.fragmentId(),
            current.agreementId(),
            current.requesterNodeId(),
            current.checksumAlgorithm(),
            current.checksum(),
            current.sizeBytes(),
            current.storedAt(),
            current.expiresAt().plusSeconds(additionalSeconds));
    custodyFragmentPort.save(extended);
    LOGGER
        .atDebug()
        .setMessage("Custody fragment TTL extended via probe success")
        .addKeyValue("event", "FRAGMENT_TTL_EXTENDED")
        .addKeyValue("fragmentId", normalized)
        .addKeyValue("additionalSeconds", additionalSeconds)
        .addKeyValue("newExpiresAt", extended.expiresAt().toString())
        .log();
  }

  @Override
  public void releaseCustody(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return;
    }
    final String normalized = fragmentId.trim();
    custodyFragmentPort.deleteByFragmentId(normalized);
    custodyFragmentPayloadPort.deleteByFragmentId(normalized);
  }

  @Override
  public void decommissionCustody(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return;
    }
    final String normalized = fragmentId.trim();
    final Optional<CustodyFragment> existing = custodyFragmentPort.findByFragmentId(normalized);
    // Drop physical custody.
    custodyFragmentPort.deleteByFragmentId(normalized);
    custodyFragmentPayloadPort.deleteByFragmentId(normalized);

    if (existing.isPresent() && negotiationService != null) {
      final String agreementId = existing.get().agreementId();
      try {
        negotiationService.cancelAgreement(agreementId);
      } catch (NegotiationException ex) {
        // Already cancelled / expired / rejected
        LOGGER
            .atDebug()
            .setMessage("Decommission: agreement already in terminal state, skipping cancel")
            .addKeyValue("fragmentId", normalized)
            .addKeyValue("agreementId", agreementId)
            .addKeyValue("reason", ex.getMessage())
            .log();
      } catch (RuntimeException ex) {
        LOGGER
            .atWarn()
            .setMessage("Decommission: agreement cancel failed unexpectedly")
            .addKeyValue("fragmentId", normalized)
            .addKeyValue("agreementId", agreementId)
            .addKeyValue("error", ex.getMessage())
            .log();
      }
    }
    LOGGER
        .atInfo()
        .setMessage("Custody fragment decommissioned via probe extension")
        .addKeyValue("event", "FRAGMENT_DECOMMISSIONED")
        .addKeyValue("fragmentId", normalized)
        .log();
  }
}
