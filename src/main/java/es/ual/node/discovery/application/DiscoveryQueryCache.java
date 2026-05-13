package es.ual.node.discovery.application;

import es.ual.node.discovery.domain.DiscoveryResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Tiny in-memory TTL cache over {@link DiscoveryService#discover} results, used by the origin to
 * avoid hammering the supernode on rafagas of uploads. The cache key is meant to capture all inputs
 * that affect the discover() output so two distinct requests cannot collide.
 *
 * <p>TTL default 30s (configurable via {@code node.discovery.cache.ttl-seconds}). The freshness
 * filter on the supernode side is 300s by default, a 30s cache is well below it, so the worst case
 * is the origin uses a candidate that just expired in the supernode at the moment of the upload
 * (the upload then aborts via {@link
 * es.ual.node.filesystem.application.InsufficientCustodiansException} or fails on a single fragment
 * storage attempt; the next upload re-queries fresh data).
 */
public class DiscoveryQueryCache {

  private final ConcurrentHashMap<String, CachedEntry> entries = new ConcurrentHashMap<>();
  private final Clock clock;
  private final long ttlSeconds;

  /**
   * Creates cache.
   *
   * @param clock clock used for expiry computation
   * @param ttlSeconds entry lifetime in seconds (must be greater than zero)
   */
  public DiscoveryQueryCache(final Clock clock, final long ttlSeconds) {
    if (clock == null) {
      throw new IllegalArgumentException("clock must not be null");
    }
    if (ttlSeconds <= 0) {
      throw new IllegalArgumentException("ttlSeconds must be greater than zero");
    }
    this.clock = clock;
    this.ttlSeconds = ttlSeconds;
  }

  /**
   * Returns the cached candidate list or invokes the loader if the entry is missing/expired.
   *
   * @param key cache key (must be non-blank)
   * @param loader supplier invoked on miss/expiry
   * @return candidate list (never null)
   */
  public List<DiscoveryResponse.CandidateNode> getOrCompute(
      final String key, final Supplier<List<DiscoveryResponse.CandidateNode>> loader) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    if (loader == null) {
      throw new IllegalArgumentException("loader must not be null");
    }

    final Instant now = clock.instant();
    final CachedEntry existing = entries.get(key);
    if (existing != null && existing.expiresAt().isAfter(now)) {
      return existing.candidates();
    }

    final List<DiscoveryResponse.CandidateNode> fresh = loader.get();
    final List<DiscoveryResponse.CandidateNode> snapshot =
        fresh == null ? List.of() : List.copyOf(fresh);
    entries.put(key, new CachedEntry(snapshot, now.plusSeconds(ttlSeconds)));
    return snapshot;
  }

  /** Clears all cached entries. Useful for tests and operator-driven flushes. */
  public void invalidate() {
    entries.clear();
  }

  private record CachedEntry(List<DiscoveryResponse.CandidateNode> candidates, Instant expiresAt) {}
}
