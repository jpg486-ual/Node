package es.ual.node.recovery.ports.out;

import es.ual.node.filesystem.domain.FsEntry;

/**
 * Outbound port que abstrae las dos operaciones del filesystem module necesarias para el recompose
 * total del {@link es.ual.node.recovery.application.FileIntegrityRiskOrchestrator}:
 *
 * <ol>
 *   <li>{@link #reconstructFileBytes} — descarga + RS-decode desde k placements OK.
 *   <li>{@link #reUploadTotal} — re-upload del archivo entero, regenerando fileId, manifest y
 *       placements. Esto sobrescribe atómicamente y libera fragments huérfanos en peers via probes.
 * </ol>
 *
 * <p>El adapter producción ({@code FileContentDistributionRecomposeAdapter}) delega ambas
 * operaciones al servicio existente {@link
 * es.ual.node.filesystem.application.FileContentDistributionService}.
 */
public interface FileRecomposePort {

  /**
   * Descarga + reconstruct del archivo desde k placements OK.
   *
   * @param fileId identificador del archivo
   * @return bytes reconstruidos
   */
  byte[] reconstructFileBytes(String fileId);

  /**
   * Re-upload total: distribuye los bytes como upload normal. Internamente regenera el fileId,
   * elimina placements/manifest viejos y crea unos nuevos.
   *
   * @param entry FsEntry asociado al archivo (mismo path/usuario que el upload original)
   * @param bytes bytes reconstruidos
   */
  void reUploadTotal(FsEntry entry, byte[] bytes);
}
