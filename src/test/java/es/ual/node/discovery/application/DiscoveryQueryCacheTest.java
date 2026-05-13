package es.ual.node.discovery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import es.ual.node.discovery.domain.DiscoveryResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DiscoveryQueryCache}. */
class DiscoveryQueryCacheTest {

  @Test
  void rejectsInvalidConstructorArgs() {
    assertThrows(IllegalArgumentException.class, () -> new DiscoveryQueryCache(null, 30L));
    assertThrows(
        IllegalArgumentException.class, () -> new DiscoveryQueryCache(Clock.systemUTC(), 0L));
  }

  @Test
  void rejectsBlankKeyAndNullLoader() {
    final DiscoveryQueryCache cache = new DiscoveryQueryCache(Clock.systemUTC(), 30L);
    assertThrows(IllegalArgumentException.class, () -> cache.getOrCompute("", List::of));
    assertThrows(IllegalArgumentException.class, () -> cache.getOrCompute("k", null));
  }

  @Test
  void cacheHitReusesPreviousValue() {
    final DiscoveryQueryCache cache = new DiscoveryQueryCache(Clock.systemUTC(), 30L);
    final AtomicInteger calls = new AtomicInteger(0);
    final List<DiscoveryResponse.CandidateNode> first =
        cache.getOrCompute(
            "k1",
            () -> {
              calls.incrementAndGet();
              return List.of(new DiscoveryResponse.CandidateNode("a", "http://a:8080", 1024L));
            });
    final List<DiscoveryResponse.CandidateNode> second =
        cache.getOrCompute(
            "k1",
            () -> {
              calls.incrementAndGet();
              return List.of(new DiscoveryResponse.CandidateNode("b", "http://b:8080", 2048L));
            });

    assertEquals(1, calls.get());
    assertSame(first, second);
  }

  @Test
  void cacheRefreshesAfterTtlExpires() {
    final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-05-03T10:00:00Z"));
    final Clock movableClock =
        new Clock() {
          @Override
          public Instant instant() {
            return now.get();
          }

          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(final java.time.ZoneId zone) {
            return this;
          }
        };
    final DiscoveryQueryCache cache = new DiscoveryQueryCache(movableClock, 30L);
    final AtomicInteger calls = new AtomicInteger(0);

    cache.getOrCompute(
        "k1",
        () -> {
          calls.incrementAndGet();
          return List.of(new DiscoveryResponse.CandidateNode("a", "http://a:8080", 1024L));
        });
    now.set(now.get().plusSeconds(60));
    cache.getOrCompute(
        "k1",
        () -> {
          calls.incrementAndGet();
          return List.of(new DiscoveryResponse.CandidateNode("a", "http://a:8080", 1024L));
        });

    assertEquals(2, calls.get());
  }

  @Test
  void distinctKeysAreCachedIndependently() {
    final DiscoveryQueryCache cache = new DiscoveryQueryCache(Clock.systemUTC(), 30L);
    final AtomicInteger calls = new AtomicInteger(0);

    cache.getOrCompute(
        "k1",
        () -> {
          calls.incrementAndGet();
          return List.of(new DiscoveryResponse.CandidateNode("a", "http://a:8080", 1024L));
        });
    cache.getOrCompute(
        "k2",
        () -> {
          calls.incrementAndGet();
          return List.of(new DiscoveryResponse.CandidateNode("b", "http://b:8080", 2048L));
        });

    assertEquals(2, calls.get());
  }
}
