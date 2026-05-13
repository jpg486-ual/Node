package es.ual.node.custodyliveness.adapters.out.memory;

import es.ual.node.custodyliveness.domain.OriginCustodianHealth;
import es.ual.node.custodyliveness.ports.out.OriginCustodianHealthPort;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory adapter para {@link OriginCustodianHealthPort} */
public class InMemoryOriginCustodianHealthPort implements OriginCustodianHealthPort {

  private final ConcurrentMap<String, OriginCustodianHealth> store = new ConcurrentHashMap<>();

  @Override
  public void upsertOnInboundProbe(
      final String custodianNodeId, final String custodianBaseUrl, final Instant now) {
    if (custodianNodeId == null || custodianNodeId.isBlank()) {
      throw new IllegalArgumentException("custodianNodeId must not be blank");
    }
    if (custodianBaseUrl == null || custodianBaseUrl.isBlank()) {
      throw new IllegalArgumentException("custodianBaseUrl must not be blank");
    }
    if (now == null) {
      throw new IllegalArgumentException("now must not be null");
    }
    store.put(
        custodianNodeId.trim(),
        OriginCustodianHealth.onInboundProbe(custodianNodeId, custodianBaseUrl, now));
  }

  @Override
  public Optional<OriginCustodianHealth> findById(final String custodianNodeId) {
    if (custodianNodeId == null || custodianNodeId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(store.get(custodianNodeId.trim()));
  }

  @Override
  public List<OriginCustodianHealth> findSilentCustodians(final Instant threshold) {
    if (threshold == null) {
      return List.of();
    }
    return store.values().stream()
        .filter(r -> r.lastInboundProbeAt() == null || r.lastInboundProbeAt().isBefore(threshold))
        .sorted(Comparator.comparing(OriginCustodianHealth::custodianNodeId))
        .toList();
  }

  @Override
  public void save(final OriginCustodianHealth record) {
    if (record == null) {
      throw new IllegalArgumentException("record must not be null");
    }
    store.put(record.custodianNodeId(), record);
  }
}
