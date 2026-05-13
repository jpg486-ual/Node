package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.filesystem.domain.FileUploadSession;
import es.ual.node.filesystem.domain.FileUploadSessionStatus;
import es.ual.node.filesystem.ports.out.FileUploadSessionPort;
import es.ual.node.persistence.jpa.FileUploadSessionJpaEntity;
import es.ual.node.persistence.jpa.FileUploadSessionJpaRepository;
import java.util.Optional;

/** PostgreSQL adapter for upload session persistence. */
public class PostgresFileUploadSessionPort implements FileUploadSessionPort {

  private final FileUploadSessionJpaRepository repository;

  /** Creates adapter. */
  public PostgresFileUploadSessionPort(final FileUploadSessionJpaRepository repository) {
    if (repository == null) {
      throw new IllegalArgumentException("repository must not be null");
    }
    this.repository = repository;
  }

  @Override
  public void save(final FileUploadSession session) {
    final FileUploadSessionJpaEntity entity = new FileUploadSessionJpaEntity();
    entity.setSessionId(session.sessionId());
    entity.setUsername(session.username());
    entity.setEntryId(session.entryId());
    entity.setExpectedSizeBytes(session.expectedSizeBytes());
    entity.setExpectedChecksum(session.expectedChecksum());
    entity.setUploadedBytes(session.uploadedBytes());
    entity.setStatus(session.status().name());
    entity.setCreatedAt(session.createdAt());
    entity.setUpdatedAt(session.updatedAt());
    entity.setCompletedAt(session.completedAt());
    repository.save(entity);
  }

  @Override
  public Optional<FileUploadSession> findByUsernameAndSessionId(
      final String username, final String sessionId) {
    return repository
        .findByUsernameAndSessionId(username, sessionId)
        .map(
            entity ->
                new FileUploadSession(
                    entity.getSessionId(),
                    entity.getUsername(),
                    entity.getEntryId(),
                    entity.getExpectedSizeBytes(),
                    entity.getExpectedChecksum(),
                    entity.getUploadedBytes(),
                    FileUploadSessionStatus.valueOf(entity.getStatus()),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt(),
                    entity.getCompletedAt()));
  }
}
