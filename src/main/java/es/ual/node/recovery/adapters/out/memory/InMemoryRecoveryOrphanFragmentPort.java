package es.ual.node.recovery.adapters.out.memory;

import es.ual.node.recovery.domain.RecoveryOrphanFragment;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPort;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory implementation of recovery orphan fragment port. */
public final class InMemoryRecoveryOrphanFragmentPort implements RecoveryOrphanFragmentPort {

  private final Map<String, RecoveryOrphanFragment> fragmentsById = new ConcurrentHashMap<>();

  @Override
  public void save(final RecoveryOrphanFragment fragment) {
    if (fragment == null) {
      throw new IllegalArgumentException("fragment must not be null");
    }
    fragmentsById.put(fragment.fragmentId(), fragment);
  }

  @Override
  public Optional<RecoveryOrphanFragment> findByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(fragmentsById.get(fragmentId.trim()));
  }

  @Override
  public List<RecoveryOrphanFragment> findByRequesterNodeId(final String requesterNodeId) {
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      return List.of();
    }
    final String key = requesterNodeId.trim();
    return fragmentsById.values().stream()
        .filter(value -> key.equals(value.requesterNodeId()))
        .toList();
  }

  @Override
  public List<RecoveryOrphanFragment> findAll() {
    return fragmentsById.values().stream()
        .sorted(
            Comparator.comparing(RecoveryOrphanFragment::storedAt, Comparator.reverseOrder())
                .thenComparing(RecoveryOrphanFragment::fragmentId))
        .toList();
  }

  @Override
  public List<String> findAllFragmentIds(final int limit) {
    final int safeLimit = Math.max(1, limit);
    return fragmentsById.values().stream()
        .sorted(Comparator.comparing(RecoveryOrphanFragment::storedAt).reversed())
        .limit(safeLimit)
        .map(RecoveryOrphanFragment::fragmentId)
        .toList();
  }

  @Override
  public void deleteByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return;
    }
    fragmentsById.remove(fragmentId.trim());
  }
}
