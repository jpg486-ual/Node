package es.ual.node.custodyliveness.domain;

/** Fragment descriptor included in custody liveness probes. */
public record CustodyProbeFragment(
    String fragmentId, String agreementId, String checksum, long sizeBytes) {}
