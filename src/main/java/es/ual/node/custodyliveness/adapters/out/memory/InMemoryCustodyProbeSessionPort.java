package es.ual.node.custodyliveness.adapters.out.memory;

import es.ual.node.custodyliveness.domain.CustodyProbeDirection;
import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import es.ual.node.custodyliveness.ports.out.CustodyProbeSessionPort;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory custody liveness session repository. */
public class InMemoryCustodyProbeSessionPort implements CustodyProbeSessionPort {

  private final ConcurrentMap<String, CustodyProbeSession> byId = new ConcurrentHashMap<>();

  @Override
  public void save(final CustodyProbeSession session) {
    if (session == null || session.sessionId() == null || session.sessionId().isBlank()) {
      throw new IllegalArgumentException("session with non-blank sessionId is required");
    }
    byId.put(session.sessionId().trim(), session);
  }

  @Override
  public Optional<CustodyProbeSession> findById(final String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(byId.get(sessionId.trim()));
  }

  @Override
  public List<CustodyProbeSession> findByRemoteNodeId(final String remoteNodeId) {
    if (remoteNodeId == null || remoteNodeId.isBlank()) {
      return List.of();
    }
    final String key = remoteNodeId.trim();
    return byId.values().stream()
        .filter(value -> key.equals(value.remoteNodeId()))
        .sorted(Comparator.comparing(CustodyProbeSession::updatedAt).reversed())
        .toList();
  }

  @Override
  public List<CustodyProbeSession> findAll() {
    return byId.values().stream()
        .sorted(
            Comparator.comparing(CustodyProbeSession::updatedAt, Comparator.reverseOrder())
                .thenComparing(CustodyProbeSession::createdAt, Comparator.reverseOrder())
                .thenComparing(CustodyProbeSession::sessionId))
        .toList();
  }

  @Override
  public List<CustodyProbeSession> findDueOutbound(final Instant now, final int limit) {
    if (now == null || limit <= 0) {
      return List.of();
    }
    return byId.values().stream()
        .filter(value -> value.direction() == CustodyProbeDirection.OUTBOUND)
        .filter(value -> value.nextAttemptAt() != null && !value.nextAttemptAt().isAfter(now))
        .sorted(Comparator.comparing(CustodyProbeSession::nextAttemptAt))
        .limit(limit)
        .toList();
  }
}
