package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * JPA entity para los bloques de un FileManifest persistidos en {@code client_file_manifest_block}.
 * Una fila por bloque RS, para manifests legacy single-block hay UNA fila sintética con {@code
 * blockIndex=0}, {@code blockSizeBytes=originalSizeBytes}, {@code blockHash=originalFileHash}; el
 * flag {@code multi_block} en {@link ClientFileManifestJpaEntity} distingue ambos modos. El campo
 * {@code fragmentHashesJson} es la única fuente de los hashes RS de cada bloque (no es shadow, los
 * hashes no son derivables de ningún otro dato).
 */
@Entity
@Table(name = "client_file_manifest_block")
@IdClass(ClientFileManifestBlockJpaEntity.PK.class)
public class ClientFileManifestBlockJpaEntity {

  @Id
  @Column(name = "file_id", nullable = false, length = 64)
  private String fileId;

  @Id
  @Column(name = "block_index", nullable = false)
  private int blockIndex;

  @Column(name = "block_size_bytes", nullable = false)
  private long blockSizeBytes;

  @Column(name = "block_hash", nullable = false, length = 64)
  private String blockHash;

  @Column(name = "fragment_hashes_json", nullable = false, columnDefinition = "TEXT")
  private String fragmentHashesJson;

  public String getFileId() {
    return fileId;
  }

  public void setFileId(final String fileId) {
    this.fileId = fileId;
  }

  public int getBlockIndex() {
    return blockIndex;
  }

  public void setBlockIndex(final int blockIndex) {
    this.blockIndex = blockIndex;
  }

  public long getBlockSizeBytes() {
    return blockSizeBytes;
  }

  public void setBlockSizeBytes(final long blockSizeBytes) {
    this.blockSizeBytes = blockSizeBytes;
  }

  public String getBlockHash() {
    return blockHash;
  }

  public void setBlockHash(final String blockHash) {
    this.blockHash = blockHash;
  }

  public String getFragmentHashesJson() {
    return fragmentHashesJson;
  }

  public void setFragmentHashesJson(final String fragmentHashesJson) {
    this.fragmentHashesJson = fragmentHashesJson;
  }

  /** Composite primary key class. */
  public static final class PK implements Serializable {

    private static final long serialVersionUID = 1L;

    private String fileId;
    private int blockIndex;

    public PK() {}

    public PK(final String fileId, final int blockIndex) {
      this.fileId = fileId;
      this.blockIndex = blockIndex;
    }

    public String getFileId() {
      return fileId;
    }

    public void setFileId(final String fileId) {
      this.fileId = fileId;
    }

    public int getBlockIndex() {
      return blockIndex;
    }

    public void setBlockIndex(final int blockIndex) {
      this.blockIndex = blockIndex;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) return true;
      if (!(o instanceof PK other)) return false;
      return blockIndex == other.blockIndex && Objects.equals(fileId, other.fileId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(fileId, blockIndex);
    }
  }
}
