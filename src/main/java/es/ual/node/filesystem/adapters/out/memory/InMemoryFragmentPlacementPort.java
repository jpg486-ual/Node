package es.ual.node.filesystem.adapters.out.memory;

import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.ports.out.FragmentPlacementPort;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory adapter for {@link FragmentPlacementPort}. */
public class InMemoryFragmentPlacementPort implements FragmentPlacementPort {

  private final Map<String, List<FragmentPlacement>> placementsByFileId = new ConcurrentHashMap<>();

  @Override
  public void save(final FragmentPlacement placement) {
    if (placement == null) {
      throw new IllegalArgumentException("placement must not be null");
    }
    placementsByFileId
        .computeIfAbsent(placement.fileId(), key -> new CopyOnWriteArrayList<>())
        .add(placement);
  }

  @Override
  public List<FragmentPlacement> findByFileId(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return List.of();
    }
    final List<FragmentPlacement> placements = placementsByFileId.get(fileId.trim());
    if (placements == null) {
      return List.of();
    }
    return placements.stream()
        .sorted(
            Comparator.comparingInt(FragmentPlacement::blockIndex)
                .thenComparingInt(FragmentPlacement::fragmentIndex))
        .toList();
  }

  @Override
  public void deleteByFileId(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return;
    }
    placementsByFileId.remove(fileId.trim());
  }

  @Override
  public List<FragmentPlacement> findAll() {
    return placementsByFileId.values().stream()
        .flatMap(List::stream)
        .sorted(
            Comparator.comparing(FragmentPlacement::fileId)
                .thenComparingInt(FragmentPlacement::blockIndex)
                .thenComparingInt(FragmentPlacement::fragmentIndex))
        .toList();
  }

  @Override
  public void deleteByFileIdAndFragmentId(final String fileId, final String fragmentId) {
    if (fileId == null || fileId.isBlank() || fragmentId == null || fragmentId.isBlank()) {
      return;
    }
    final List<FragmentPlacement> existing = placementsByFileId.get(fileId.trim());
    if (existing == null) {
      return;
    }
    existing.removeIf(p -> fragmentId.trim().equals(p.fragmentId()));
  }

  @Override
  public List<FragmentPlacement> findByCustodianNodeId(final String custodianNodeId) {
    if (custodianNodeId == null || custodianNodeId.isBlank()) {
      return List.of();
    }
    final String normalized = custodianNodeId.trim();
    return placementsByFileId.values().stream()
        .flatMap(List::stream)
        .filter(p -> normalized.equals(p.custodianNodeId()))
        .sorted(
            Comparator.comparing(FragmentPlacement::fileId)
                .thenComparingInt(FragmentPlacement::blockIndex)
                .thenComparingInt(FragmentPlacement::fragmentIndex))
        .toList();
  }

  @Override
  public void updateHealth(final FragmentPlacement updated) {
    if (updated == null) {
      throw new IllegalArgumentException("updated must not be null");
    }
    final List<FragmentPlacement> existing = placementsByFileId.get(updated.fileId());
    if (existing == null) {
      return;
    }
    final int idx =
        java.util.stream.IntStream.range(0, existing.size())
            .filter(i -> existing.get(i).fragmentId().equals(updated.fragmentId()))
            .findFirst()
            .orElse(-1);
    if (idx < 0) {
      return;
    }
    existing.set(idx, updated);
  }

  /** Returns the current count of stored placements across all files (test helper). */
  public int size() {
    return placementsByFileId.values().stream().mapToInt(List::size).sum();
  }
}
