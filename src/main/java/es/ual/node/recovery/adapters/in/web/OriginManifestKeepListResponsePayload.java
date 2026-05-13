package es.ual.node.recovery.adapters.in.web;

import java.util.List;

/**
 * Response payload for {@code GET /ops/tutor/manifest-keep-list}. El origen devuelve al tutor la
 * whitelist completa de manifests que actualmente posee localmente, cualquier manifest que el tutor
 * custodie y NO aparezca en esta lista debe ser purgado por el tutor (whitelist invertida).
 */
public record OriginManifestKeepListResponsePayload(List<String> fileIds) {

  /** Compact constructor — defensive copy + null normalisation. */
  public OriginManifestKeepListResponsePayload {
    fileIds = fileIds == null ? List.of() : List.copyOf(fileIds);
  }
}
