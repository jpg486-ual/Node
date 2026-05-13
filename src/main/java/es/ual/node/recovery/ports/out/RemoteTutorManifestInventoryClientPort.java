package es.ual.node.recovery.ports.out;

import java.util.List;

/**
 * Outbound port (origen-side) for invoking the tutor's {@code GET
 * /recovery/file-manifests/inventory} endpoint. El supervisado, si lleva tiempo sin recibir
 * keep-list del tutor, le pide al tutor el inventario completo de manifests que custodia para él.
 *
 * <p>Implementación MUST sign the canonical {@code (method, path, query, nonce, timestamp)} tuple.
 */
public interface RemoteTutorManifestInventoryClientPort {

  /**
   * Issues {@code GET /recovery/file-manifests/inventory} against {@code tutorBaseUrl}. Returns the
   * list of file ids the tutor currently custodies for the calling node.
   *
   * @param tutorBaseUrl base URL of the tutor (no trailing slash)
   * @return inventory of file ids (may be empty)
   * @throws IllegalStateException on transport, signature, or non-2xx HTTP response
   */
  List<String> fetchInventory(String tutorBaseUrl);
}
