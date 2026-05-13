package es.ual.node.custodyliveness.domain;

/** Lifecycle status for custody liveness sessions. */
public enum CustodyProbeStatus {
  ACTIVE,
  PROBING,
  SUSPECT,
  UNRESPONSIVE,
  ESCALATED,
  RELEASED
}
