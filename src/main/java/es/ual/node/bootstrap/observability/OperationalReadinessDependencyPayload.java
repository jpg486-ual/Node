package es.ual.node.bootstrap.observability;

/** Dependency-level readiness payload for one critical or supporting capability. */
public record OperationalReadinessDependencyPayload(
    String status, boolean critical, String detail) {

  public static final String STATUS_UP = "UP";
  public static final String STATUS_DEGRADED = "DEGRADED";
  public static final String STATUS_DOWN = "DOWN";

  /** Validates dependency payload content. */
  public OperationalReadinessDependencyPayload {
    if (status == null || status.isBlank()) {
      throw new IllegalArgumentException("status must not be blank");
    }
    if (detail == null || detail.isBlank()) {
      throw new IllegalArgumentException("detail must not be blank");
    }
  }

  /** Returns whether dependency is healthy. */
  public boolean isUp() {
    return STATUS_UP.equals(status);
  }

  /** Returns whether dependency is unavailable. */
  public boolean isDown() {
    return STATUS_DOWN.equals(status);
  }
}
