package es.ual.node.custodyliveness.domain;

/** Escalation policy applied when remote custody liveness becomes unresponsive. */
public enum CustodyEscalationPolicy {
  KEEP_AND_ALERT,
  RETURN_TO_TUTOR
}
