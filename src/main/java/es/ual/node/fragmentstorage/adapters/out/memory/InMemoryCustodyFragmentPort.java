package es.ual.node.fragmentstorage.adapters.out.memory;

import es.ual.node.fragmentstorage.domain.CustodyFragment;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPort;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory implementation of custody fragment port. */
public final class InMemoryCustodyFragmentPort implements CustodyFragmentPort {

  private final Map<String, CustodyFragment> fragmentsById = new ConcurrentHashMap<>();

  @Override
  public void save(final CustodyFragment fragment) {
    if (fragment == null) {
      throw new IllegalArgumentException("fragment must not be null");
    }
    fragmentsById.put(fragment.fragmentId(), fragment);
  }

  @Override
  public Optional<CustodyFragment> findByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(fragmentsById.get(fragmentId.trim()));
  }

  @Override
  public List<CustodyFragment> findByRequesterNodeId(final String requesterNodeId) {
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      return List.of();
    }
    final String key = requesterNodeId.trim();
    return fragmentsById.values().stream()
        .filter(value -> key.equals(value.requesterNodeId()))
        .toList();
  }

  @Override
  public List<CustodyFragment> findAll() {
    return fragmentsById.values().stream()
        .sorted(
            Comparator.comparing(CustodyFragment::storedAt, Comparator.reverseOrder())
                .thenComparing(CustodyFragment::fragmentId))
        .toList();
  }

  @Override
  public List<CustodyFragment> findExpired(final Instant threshold, final int limit) {
    if (threshold == null) {
      throw new IllegalArgumentException("threshold must not be null");
    }
    final int capped = Math.max(1, limit);
    return fragmentsById.values().stream()
        .filter(value -> value.expiresAt() != null && value.expiresAt().isBefore(threshold))
        .sorted(
            Comparator.comparing(CustodyFragment::expiresAt)
                .thenComparing(CustodyFragment::fragmentId))
        .limit(capped)
        .toList();
  }

  @Override
  public void deleteByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return;
    }
    fragmentsById.remove(fragmentId.trim());
  }

  @Override
  public long totalSizeBytes() {
    return fragmentsById.values().stream().mapToLong(CustodyFragment::sizeBytes).sum();
  }
}
