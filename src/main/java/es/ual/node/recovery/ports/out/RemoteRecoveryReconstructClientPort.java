package es.ual.node.recovery.ports.out;

import java.util.List;

/**
 * Outbound port para invocar el endpoint {@code POST /recovery/fragments/reconstruct} en el tutor.
 * Usado por {@code NodeFsRestoreService} en strategy {@code BYTES_FROM_TUTOR}: tras el
 * RETURN_TO_TUTOR, los fragments del archivo viven en el {@code recovery_orphan_fragment} del
 * tutor, y el origen recuperado recupera los bytes reconstruidos directamente desde ahí en lugar de
 * intentar reconstruct desde peers (que ya no los tienen).
 *
 * <p>Una llamada por bloque RS: el tutor reconstruye el block desde k orphan fragments y devuelve
 * los bytes originales del bloque. El caller concatena bloques en orden para reconstituir el file.
 */
public interface RemoteRecoveryReconstructClientPort {

  /**
   * Pulla los bytes reconstruidos de un bloque RS desde el tutor.
   *
   * @param tutorBaseUrl base URL del tutor (no terminada en slash)
   * @param fileId identificador del file
   * @param expectedOriginalHash SHA-256 hex del file completo (NO del bloque), el tutor lo usa para
   *     verificar integridad post-decode
   * @param redundancyN n del scheme RS
   * @param redundancyK k del scheme RS
   * @param symbolSize symbolSize del scheme RS
   * @param fragments lista de referencias al bloque (n fragmentos: k data + (n-k) parity), todos
   *     con el mismo blockIndex implícito
   * @return bytes reconstruidos del bloque
   * @throws IllegalStateException si HTTP non-2xx o checksum verification falla
   */
  byte[] reconstruct(
      String tutorBaseUrl,
      String fileId,
      String expectedOriginalHash,
      int redundancyN,
      int redundancyK,
      int symbolSize,
      List<FragmentReference> fragments);

  /** Referencia compacta a un fragment al construir la request de reconstruct. */
  record FragmentReference(String fragmentId, int index, boolean parity) {}
}
