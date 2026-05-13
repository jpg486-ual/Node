package es.ual.node.custodyliveness.adapters.out.custody;

import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentInventoryPort;
import es.ual.node.fragmentstorage.domain.CustodyFragment;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPort;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter that exposes the local custody fragment store to the {@code custodyliveness} module.
 * Reads <strong>only</strong> custody recovery orphan fragments, held at the tutor after
 * RETURN_TO_TUTOR escalation. Do not participate in probe cycles because their requester is the
 * failed node, not a peer to be monitored.
 */
public class CustodyFragmentInventoryAdapter implements CustodyFragmentInventoryPort {

  private final CustodyFragmentPort custodyFragmentPort;

  public CustodyFragmentInventoryAdapter(final CustodyFragmentPort custodyFragmentPort) {
    if (custodyFragmentPort == null) {
      throw new IllegalArgumentException("custodyFragmentPort must not be null");
    }
    this.custodyFragmentPort = custodyFragmentPort;
  }

  @Override
  public List<CustodyProbeFragment> findCustodiedForRequester(
      final String requesterNodeId, final Instant now) {
    if (requesterNodeId == null || requesterNodeId.isBlank() || now == null) {
      return List.of();
    }
    // No filtramos por TTL: un fragment expirado sigue siendo legítimo de probear. Si el
    // origen lo confirma en la respuesta del probe (direct o keep-list), se renueva el TTL
    // vía renewStillRequiredFragments / extendCustody. Si el origen NO lo confirma (e.g.
    // hizo recompose tras restore y reemitió fragments con nuevo fileId), el keep-list
    // path lo decommissiona. Filtrar aquí desactivaba ambos comportamientos.
    return custodyFragmentPort.findByRequesterNodeId(requesterNodeId.trim()).stream()
        .map(this::toProbeFragment)
        .toList();
  }

  @Override
  public List<String> listDistinctRequesterNodeIds() {
    final Set<String> distinct = new LinkedHashSet<>();
    for (CustodyFragment fragment : custodyFragmentPort.findAll()) {
      if (fragment.requesterNodeId() != null && !fragment.requesterNodeId().isBlank()) {
        distinct.add(fragment.requesterNodeId());
      }
    }
    return List.copyOf(distinct);
  }

  @Override
  public List<ExpiredCustodyEntry> findExpiredCustodied(final Instant threshold, final int limit) {
    if (threshold == null) {
      return List.of();
    }
    final int capped = Math.max(1, limit);
    return custodyFragmentPort.findExpired(threshold, capped).stream()
        .map(
            value ->
                new ExpiredCustodyEntry(
                    value.fragmentId(),
                    value.agreementId(),
                    value.requesterNodeId(),
                    value.checksum(),
                    value.sizeBytes(),
                    value.expiresAt()))
        .toList();
  }

  private CustodyProbeFragment toProbeFragment(final CustodyFragment stored) {
    return new CustodyProbeFragment(
        stored.fragmentId(), stored.agreementId(), stored.checksum(), stored.sizeBytes());
  }
}
