package es.ual.node.custodyliveness.ports.out;

import es.ual.node.fragmentstorage.domain.CustodyFragment;
import java.util.Optional;

/**
 * Lifecycle controls for custodied fragments, exposed to the {@code custodyliveness} module Lives
 * on the {@code custodyliveness} side of the hexagonal frontier (Cockburn 2005) so the dependency
 * direction stays {@code custodyliveness} → port → {@code fragmentstorage} adapter.
 *
 * <p>{@link #extendCustody(String, long)} is the primary operation {@code
 * CustodyLivenessService.processSingleOutboundSession} iterates {@code
 * CustodyProbeResponse.stillRequiredFragmentIds()} and extends the TTL of those fragments whose
 * remaining life is below the configured renewal horizon.
 *
 * <p>{@link #releaseCustody(String)} is intentionally defined here without callers.
 */
public interface CustodyFragmentLifecyclePort {

  /**
   * Returns metadata for a fragment held in local custody, or empty when absent.
   *
   * @param fragmentId fragment id
   * @return current stored fragment metadata
   */
  Optional<CustodyFragment> findByFragmentId(String fragmentId);

  /**
   * Adds {@code additionalSeconds} to the {@code expiresAt} of the fragment in local custody. No-op
   * if the fragment is unknown (already expired-and-deleted by the consistency sweep, never stored,
   * or released by another node). The implementation MUST be idempotent at the level of a single
   * tick, repeated calls within the same probe cycle are protected by the caller's horizon check,
   * not the adapter.
   *
   * @param fragmentId fragment id
   * @param additionalSeconds seconds to add to current {@code expiresAt}
   */
  void extendCustody(String fragmentId, long additionalSeconds);

  /**
   * Removes the fragment from local custody (metadata + payload).
   *
   * @param fragmentId fragment id
   */
  void releaseCustody(String fragmentId);

  /**
   * Decommissions a fragment under explicit instruction from the origin: releases the custody
   * record + payload AND cancels the underlying agreement (CONFIRMED → CANCELLED) so subsequent
   * probes don't re-attempt extension. Idempotent: missing fragment or already-cancelled agreement
   * are no-op.
   *
   * <p>Consumido exclusivamente por el flujo whitelist puro {@code
   * CustodianOutboundKeepListService} llama este método para fragments que el origen deja fuera de
   * su {@code keepFragmentIds}.
   *
   * @param fragmentId fragment id to decommission
   */
  void decommissionCustody(String fragmentId);
}
