package es.ual.node.recovery.adapters.in.web;

import java.util.List;

/**
 * Response payload for {@code GET /recovery/file-manifests/inventory}. Si el supervisado lleva
 * tiempo sin recibir keep-list del tutor, le pide al tutor inventario completo de manifests que
 * custodia para él. Compara contra su {@code client_file_manifest} local y re-emite los faltantes.
 */
public record TutorManifestInventoryResponsePayload(List<String> fileIds) {

  /** Compact constructor. Defensive copy + null normalisation. */
  public TutorManifestInventoryResponsePayload {
    fileIds = fileIds == null ? List.of() : List.copyOf(fileIds);
  }
}
