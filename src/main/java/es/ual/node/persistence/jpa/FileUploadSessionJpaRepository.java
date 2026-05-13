package es.ual.node.persistence.jpa;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA repository for file upload session persistence. */
public interface FileUploadSessionJpaRepository
    extends JpaRepository<FileUploadSessionJpaEntity, String> {

  /** Finds session by username and id. */
  Optional<FileUploadSessionJpaEntity> findByUsernameAndSessionId(
      String username, String sessionId);
}
