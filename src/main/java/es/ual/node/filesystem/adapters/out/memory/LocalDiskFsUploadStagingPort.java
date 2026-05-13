package es.ual.node.filesystem.adapters.out.memory;

import es.ual.node.filesystem.ports.out.FsUploadStagingPort;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Local-disk staging adapter for resumable chunk uploads. */
public class LocalDiskFsUploadStagingPort implements FsUploadStagingPort {

  private final Path baseDirectory;

  /**
   * Creates adapter.
   *
   * @param baseDirectory staging directory
   */
  public LocalDiskFsUploadStagingPort(final Path baseDirectory) {
    if (baseDirectory == null) {
      throw new IllegalArgumentException("baseDirectory must not be null");
    }
    this.baseDirectory = baseDirectory;
  }

  @Override
  public void reset(final String username, final String sessionId) {
    final Path file = resolvePath(username, sessionId);
    try {
      Files.createDirectories(file.getParent());
      Files.deleteIfExists(file);
      Files.createFile(file);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to reset staging file", exception);
    }
  }

  @Override
  public void append(
      final String username, final String sessionId, final long offset, final byte[] chunk) {
    if (chunk == null) {
      throw new IllegalArgumentException("chunk must not be null");
    }
    final Path file = resolvePath(username, sessionId);
    try {
      Files.createDirectories(file.getParent());
      if (!Files.exists(file)) {
        Files.createFile(file);
      }
      try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
        if (raf.length() != offset) {
          throw new IllegalArgumentException("offset does not match current staged size");
        }
        raf.seek(offset);
        raf.write(chunk);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to append chunk", exception);
    }
  }

  @Override
  public Optional<byte[]> readAll(final String username, final String sessionId) {
    final Path file = resolvePath(username, sessionId);
    try {
      if (!Files.exists(file)) {
        return Optional.empty();
      }
      return Optional.of(Files.readAllBytes(file));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to read staging file", exception);
    }
  }

  @Override
  public Optional<InputStream> openInputStream(final String username, final String sessionId) {
    final Path file = resolvePath(username, sessionId);
    try {
      if (!Files.exists(file)) {
        return Optional.empty();
      }
      // Files.newInputStream is lazy/streaming: caller reads the file in chunks without
      // materializing the full content in RAM.
      return Optional.of(Files.newInputStream(file));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to open staging file stream", exception);
    }
  }

  @Override
  public void delete(final String username, final String sessionId) {
    try {
      Files.deleteIfExists(resolvePath(username, sessionId));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to delete staging file", exception);
    }
  }

  private Path resolvePath(final String username, final String sessionId) {
    final String safeUsername = sanitize(username);
    final String safeSessionId = sanitize(sessionId);
    return baseDirectory.resolve(safeUsername).resolve(safeSessionId + ".part");
  }

  private String sanitize(final String value) {
    return value == null ? "_" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
  }
}
