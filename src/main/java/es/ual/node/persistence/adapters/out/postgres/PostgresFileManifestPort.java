package es.ual.node.persistence.adapters.out.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.filesystem.ports.out.FileManifestPort;
import es.ual.node.negotiation.domain.BlockManifest;
import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.persistence.jpa.ClientFileManifestBlockJpaEntity;
import es.ual.node.persistence.jpa.ClientFileManifestBlockJpaRepository;
import es.ual.node.persistence.jpa.ClientFileManifestJpaEntity;
import es.ual.node.persistence.jpa.ClientFileManifestJpaRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter for {@link FileManifestPort}. */
public class PostgresFileManifestPort implements FileManifestPort {

  private static final TypeReference<List<String>> HASH_LIST_TYPE = new TypeReference<>() {};

  private final ClientFileManifestJpaRepository repository;
  private final ClientFileManifestBlockJpaRepository blockRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  /** Creates adapter. */
  public PostgresFileManifestPort(
      final ClientFileManifestJpaRepository repository,
      final ClientFileManifestBlockJpaRepository blockRepository,
      final ObjectMapper objectMapper,
      final Clock clock) {
    if (repository == null || blockRepository == null || objectMapper == null || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.repository = repository;
    this.blockRepository = blockRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void save(final FileManifest manifest, final String username, final String entryId) {
    if (manifest == null) {
      throw new IllegalArgumentException("manifest must not be null");
    }
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    if (entryId == null || entryId.isBlank()) {
      throw new IllegalArgumentException("entryId must not be blank");
    }

    final boolean multiBlock = manifest.blocks() != null && !manifest.blocks().isEmpty();

    final ClientFileManifestJpaEntity entity = new ClientFileManifestJpaEntity();
    entity.setFileId(manifest.fileId());
    entity.setUsername(username.trim());
    entity.setEntryId(entryId.trim());
    entity.setDirectoryPath(manifest.directoryPath());
    entity.setOriginalFileName(manifest.originalFileName());
    entity.setOriginalFileHash(manifest.originalFileHash());
    entity.setOriginalSizeBytes(manifest.originalSizeBytes());
    entity.setCompressedSizeBytes(manifest.compressedSizeBytes());
    entity.setCompressionAlgorithm(manifest.compressionAlgorithm());
    entity.setFragmentCount(manifest.fragmentCount());
    entity.setFragmentSize(manifest.fragmentSize());
    entity.setRedundancyN(manifest.redundancyN());
    entity.setRedundancyK(manifest.redundancyK());
    entity.setSymbolSize((int) manifest.fragmentSize());
    entity.setMultiBlock(multiBlock);
    entity.setCreatedAt(clock.instant());
    repository.save(entity);

    // Limpia bloques previos (idempotent on overwrite) y persiste el layout actual.
    blockRepository.deleteAllByFileId(manifest.fileId());
    final List<ClientFileManifestBlockJpaEntity> blockRows = new ArrayList<>();
    if (multiBlock) {
      for (BlockManifest block : manifest.blocks()) {
        blockRows.add(toBlockEntity(manifest.fileId(), block));
      }
    } else {
      // Legacy single-block: bloque sintético con block_index=0.
      final ClientFileManifestBlockJpaEntity synthetic = new ClientFileManifestBlockJpaEntity();
      synthetic.setFileId(manifest.fileId());
      synthetic.setBlockIndex(0);
      synthetic.setBlockSizeBytes(manifest.originalSizeBytes());
      synthetic.setBlockHash(manifest.originalFileHash());
      synthetic.setFragmentHashesJson(writeHashes(manifest.fragmentHashes()));
      blockRows.add(synthetic);
    }
    blockRepository.saveAll(blockRows);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<FileManifest> findByFileId(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return Optional.empty();
    }
    final String normalized = fileId.trim();
    return repository.findById(normalized).map(this::toDomain);
  }

  @Override
  @Transactional
  public void deleteByFileId(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return;
    }
    final String normalized = fileId.trim();
    blockRepository.deleteAllByFileId(normalized);
    repository.deleteById(normalized);
  }

  @Override
  @Transactional(readOnly = true)
  public List<String> findAllFileIds() {
    return repository.findAll().stream().map(ClientFileManifestJpaEntity::getFileId).toList();
  }

  private FileManifest toDomain(final ClientFileManifestJpaEntity entity) {
    final List<ClientFileManifestBlockJpaEntity> blockRows =
        blockRepository.findByFileIdOrderByBlockIndexAsc(entity.getFileId());
    if (blockRows.isEmpty()) {
      throw new IllegalStateException(
          "client_file_manifest_block rows missing for fileId=" + entity.getFileId());
    }

    final List<BlockManifest> blocks;
    final List<String> fragmentHashes;
    if (entity.isMultiBlock()) {
      blocks = new ArrayList<>(blockRows.size());
      final List<String> aggregate = new ArrayList<>();
      for (ClientFileManifestBlockJpaEntity row : blockRows) {
        final List<String> hashes = readHashes(row.getFragmentHashesJson());
        blocks.add(
            new BlockManifest(
                row.getBlockIndex(), row.getBlockSizeBytes(), row.getBlockHash(), hashes));
        aggregate.addAll(hashes);
      }
      fragmentHashes = aggregate;
    } else {
      // Legacy single-block — exactamente 1 fila sintética.
      blocks = List.of();
      fragmentHashes = readHashes(blockRows.get(0).getFragmentHashesJson());
    }

    return new FileManifest(
        entity.getFileId(),
        entity.getDirectoryPath(),
        entity.getOriginalFileName(),
        entity.getOriginalSizeBytes(),
        entity.getCompressedSizeBytes(),
        entity.getCompressionAlgorithm(),
        entity.getOriginalFileHash(),
        entity.getFragmentCount(),
        entity.getFragmentSize(),
        entity.getRedundancyN(),
        entity.getRedundancyK(),
        fragmentHashes,
        blocks);
  }

  private ClientFileManifestBlockJpaEntity toBlockEntity(
      final String fileId, final BlockManifest block) {
    final ClientFileManifestBlockJpaEntity row = new ClientFileManifestBlockJpaEntity();
    row.setFileId(fileId);
    row.setBlockIndex(block.blockIndex());
    row.setBlockSizeBytes(block.blockSizeBytes());
    row.setBlockHash(block.blockHash());
    row.setFragmentHashesJson(writeHashes(block.fragmentHashes()));
    return row;
  }

  private String writeHashes(final List<String> hashes) {
    try {
      return objectMapper.writeValueAsString(hashes);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to serialize fragmentHashes", ex);
    }
  }

  private List<String> readHashes(final String json) {
    try {
      return objectMapper.readValue(json, HASH_LIST_TYPE);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to deserialize fragmentHashes", ex);
    }
  }
}
