package es.ual.node.recovery.adapters.in.web;

import java.util.List;

/**
 * HTTP response payload for {@code GET /recovery/file-manifests}: list of manifests custodied for
 * the requesting node, identified by signature header (no username exposed).
 */
public record RecoveryFileManifestListPayload(List<CustodiedFileManifestPayload> manifests) {}
