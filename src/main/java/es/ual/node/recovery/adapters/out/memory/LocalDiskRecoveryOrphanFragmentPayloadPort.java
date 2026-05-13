package es.ual.node.recovery.adapters.out.memory;

import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPayloadPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Local-disk payload store for recovery-domain orphan fragment bytes. */
public final class LocalDiskRecoveryOrphanFragmentPayloadPort
    implements RecoveryOrphanFragmentPayloadPort {

  private final Path baseDirectory;

  public LocalDiskRecoveryOrphanFragmentPayloadPort(final Path baseDirectory) {
    if (baseDirectory == null) {
      throw new IllegalArgumentException("baseDirectory must not be null");
    }
    this.baseDirectory = baseDirectory;
  }

  @Override
  public void save(final String fragmentId, final byte[] payload) {
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    if (payload == null || payload.length == 0) {
      throw new IllegalArgumentException("payload must not be null or empty");
    }
    final Path file = resolveFilePath(fragmentId.trim());
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, payload);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to store recovery orphan payload", exception);
    }
  }

  @Override
  public Optional<byte[]> findByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return Optional.empty();
    }
    final Path file = resolveFilePath(fragmentId.trim());
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readAllBytes(file));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to read recovery orphan payload", exception);
    }
  }

  @Override
  public boolean exists(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return false;
    }
    return Files.exists(resolveFilePath(fragmentId.trim()));
  }

  @Override
  public void deleteByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return;
    }
    try {
      Files.deleteIfExists(resolveFilePath(fragmentId.trim()));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to delete recovery orphan payload", exception);
    }
  }

  private Path resolveFilePath(final String fragmentId) {
    return baseDirectory.resolve(sanitize(fragmentId) + ".bin");
  }

  private String sanitize(final String value) {
    return value.replaceAll("[^a-zA-Z0-9._-]", "_");
  }
}
