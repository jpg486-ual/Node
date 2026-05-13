package es.ual.node.custodyliveness.adapters.out.probe;

import es.ual.node.custodyliveness.application.CustodyLivenessService;
import es.ual.node.fragmentstorage.ports.out.CustodyEventNotifierPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auto-bootstraps an outbound probe session against the requester (origin) the first time this node
 * accepts a custody fragment from it, leaving the module structurally inert in production unless an
 * operator triggered it explicitly).
 *
 * <p>Idempotent by construction {@link CustodyLivenessService#scheduleProbeNow(String)} returns the
 * existing session when a deduplicable OUTBOUND ACTIVE session already exists for the requester, so
 * the many fragments of a single upload coalesce into one probe session.
 *
 * <p>Failures (e.g. session port unavailable, transient repository error) are logged and swallowed:
 * the custody store has already succeeded by the time this runs and must remain durable regardless
 * of probe-wiring health.
 */
public class CustodyLivenessProbeBootstrapAdapter implements CustodyEventNotifierPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(CustodyLivenessProbeBootstrapAdapter.class);

  private final CustodyLivenessService custodyLivenessService;

  /** Creates adapter. */
  public CustodyLivenessProbeBootstrapAdapter(final CustodyLivenessService service) {
    if (service == null) {
      throw new IllegalArgumentException("custodyLivenessService must not be null");
    }
    this.custodyLivenessService = service;
  }

  @Override
  public void onCustodyFragmentStored(final String requesterNodeId) {
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      return;
    }
    try {
      custodyLivenessService.scheduleProbeNow(requesterNodeId.trim());
    } catch (RuntimeException ex) {
      LOGGER
          .atWarn()
          .setMessage("Failed to auto-bootstrap probe session after custody store")
          .addKeyValue("event", "PROBE_BOOTSTRAP_FAILED")
          .addKeyValue("requesterNodeId", requesterNodeId)
          .addKeyValue("error", ex.getMessage())
          .log();
    }
  }
}
