package es.ual.node.custodyliveness.domain;

import java.time.Instant;
import java.util.List;

/**
 * Response for custody liveness probe.
 *
 * <p>La whitelist pura es el único mecanismo para señalar al custodian qué fragments eliminar.
 */
public record CustodyProbeResponse(
    String requestId,
    List<String> stillRequiredFragmentIds,
    List<String> releasableFragmentIds,
    boolean reverseProbeRequested,
    Instant reverseProbeNotBefore,
    Instant respondedAt) {}
