package es.ual.node.persistence.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * JPA repository para los bloques de FileManifest. Una fila por bloque RS o una sintética para
 * legacy single-block.
 */
public interface ClientFileManifestBlockJpaRepository
    extends JpaRepository<ClientFileManifestBlockJpaEntity, ClientFileManifestBlockJpaEntity.PK> {

  List<ClientFileManifestBlockJpaEntity> findByFileIdOrderByBlockIndexAsc(String fileId);

  @Modifying
  @Query("DELETE FROM ClientFileManifestBlockJpaEntity b WHERE b.fileId = :fileId")
  int deleteAllByFileId(@Param("fileId") String fileId);
}
