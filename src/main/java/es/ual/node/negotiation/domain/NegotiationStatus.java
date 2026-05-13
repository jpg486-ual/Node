package es.ual.node.negotiation.domain;

/** States of a negotiation agreement lifecycle. */
public enum NegotiationStatus {
  PENDING,
  CONFIRMED,
  REJECTED,
  EXPIRED,
  CANCELLED
}
