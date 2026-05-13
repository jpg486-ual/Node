package es.ual.node.recovery.adapters.out.filesystem;

import es.ual.node.filesystem.application.FileContentDistributionService;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.recovery.ports.out.FileRecomposePort;
import java.io.ByteArrayInputStream;

/**
 * Adapter que conecta el port {@link FileRecomposePort} (módulo recovery) con el servicio existente
 * {@link FileContentDistributionService} (módulo filesystem) sin que el orchestrator dependa
 * directamente de éste último. Esto permite tests del orchestrator con stubs limpios y mantiene la
 * separación hexagonal entre módulos.
 */
public class FileContentDistributionRecomposeAdapter implements FileRecomposePort {

  private final FileContentDistributionService distributionService;

  /** Creates adapter. */
  public FileContentDistributionRecomposeAdapter(
      final FileContentDistributionService distributionService) {
    if (distributionService == null) {
      throw new IllegalArgumentException("distributionService must not be null");
    }
    this.distributionService = distributionService;
  }

  @Override
  public byte[] reconstructFileBytes(final String fileId) {
    return distributionService.reconstructDownload(fileId);
  }

  @Override
  public void reUploadTotal(final FsEntry entry, final byte[] bytes) {
    if (entry == null) {
      throw new IllegalArgumentException("entry must not be null");
    }
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException("bytes must not be empty");
    }
    distributionService.distributeUploadStreaming(
        entry.username(), entry, new ByteArrayInputStream(bytes), bytes.length);
  }
}
