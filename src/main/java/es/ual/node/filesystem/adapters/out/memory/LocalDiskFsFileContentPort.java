package es.ual.node.filesystem.adapters.out.memory;

import es.ual.node.filesystem.ports.out.FsFileContentPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Local-disk file content adapter. */
public class LocalDiskFsFileContentPort implements FsFileContentPort {

  private final Path baseDirectory;

  /**
   * Creates adapter.
   *
   * @param baseDirectory base storage directory
   */
  public LocalDiskFsFileContentPort(final Path baseDirectory) {
    if (baseDirectory == null) {
      throw new IllegalArgumentException("baseDirectory must not be null");
    }
    this.baseDirectory = baseDirectory;
  }

  @Override
  public void save(final String username, final String entryId, final byte[] content) {
    try {
      final Path file = resolveFilePath(username, entryId);
      Files.createDirectories(file.getParent());
      Files.write(file, content);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to store file content", exception);
    }
  }

  @Override
  public Optional<byte[]> find(final String username, final String entryId) {
    try {
      final Path file = resolveFilePath(username, entryId);
      if (!Files.exists(file)) {
        return Optional.empty();
      }
      return Optional.of(Files.readAllBytes(file));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to read file content", exception);
    }
  }

  @Override
  public void delete(final String username, final String entryId) {
    try {
      Files.deleteIfExists(resolveFilePath(username, entryId));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to delete file content", exception);
    }
  }

  private Path resolveFilePath(final String username, final String entryId) {
    final String safeUsername = sanitize(username);
    final String safeEntryId = sanitize(entryId);
    return baseDirectory.resolve(safeUsername).resolve(safeEntryId + ".bin");
  }

  private String sanitize(final String value) {
    return value == null ? "_" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
  }
}
