package es.ual.node.bootstrap.observability;

import java.time.Instant;
import java.util.Map;

/** Aggregated readiness payload for the node. */
public record OperationalReadinessPayload(
    String status,
    boolean acceptingTraffic,
    boolean degraded,
    Instant checkedAt,
    Map<String, OperationalReadinessDependencyPayload> dependencies) {

  public static final String STATUS_READY = "READY";
  public static final String STATUS_DEGRADED = "DEGRADED";
  public static final String STATUS_NOT_READY = "NOT_READY";

  /** Validates readiness payload and freezes dependency map. */
  public OperationalReadinessPayload {
    if (status == null || status.isBlank()) {
      throw new IllegalArgumentException("status must not be blank");
    }
    if (checkedAt == null) {
      throw new IllegalArgumentException("checkedAt must not be null");
    }
    if (dependencies == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    dependencies = Map.copyOf(dependencies);
  }
}
