package es.ual.node.persistence.adapters.out.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.persistence.jpa.RecoveryFileManifestJpaEntity;
import es.ual.node.persistence.jpa.RecoveryFileManifestJpaRepository;
import es.ual.node.recovery.domain.CustodiedFileManifest;
import es.ual.node.recovery.ports.out.CustodiedFileManifestPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Postgres adapter for {@link CustodiedFileManifestPort} */
public class PostgresCustodiedFileManifestPort implements CustodiedFileManifestPort {

  private static final TypeReference<List<String>> HASH_LIST_TYPE = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> BLOCK_LIST_TYPE =
      new TypeReference<>() {};

  private final RecoveryFileManifestJpaRepository repository;
  private final ObjectMapper objectMapper;

  /**
   * Creates adapter.
   *
   * @param repository JPA repository
   * @param objectMapper object mapper
   */
  public PostgresCustodiedFileManifestPort(
      final RecoveryFileManifestJpaRepository repository, final ObjectMapper objectMapper) {
    if (repository == null || objectMapper == null) {
      throw new IllegalArgumentException("Dependencies must not be null");
    }
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public void save(final CustodiedFileManifest manifest) {
    if (manifest == null) {
      throw new IllegalArgumentException("manifest must not be null");
    }
    repository.save(toEntity(manifest));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<CustodiedFileManifest> findByFileId(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return Optional.empty();
    }
    return repository.findById(fileId.trim()).map(this::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<CustodiedFileManifest> findByRequesterNodeId(final String requesterNodeId) {
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      return List.of();
    }
    return repository.findByRequesterNodeIdOrderByStoredAtDesc(requesterNodeId.trim()).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  @Transactional
  public boolean deleteByFileId(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return false;
    }
    final String normalized = fileId.trim();
    if (!repository.existsById(normalized)) {
      return false;
    }
    repository.deleteById(normalized);
    return true;
  }

  @Override
  @Transactional
  public int deleteByFileIds(final Iterable<String> fileIds) {
    if (fileIds == null) {
      return 0;
    }
    final List<String> ids =
        java.util.stream.StreamSupport.stream(fileIds.spliterator(), false)
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .toList();
    if (ids.isEmpty()) {
      return 0;
    }
    return repository.deleteByFileIds(ids);
  }

  @Override
  @Transactional
  public int markSupervisedCheckOk(final String requesterNodeId, final Instant at) {
    if (requesterNodeId == null || requesterNodeId.isBlank() || at == null) {
      return 0;
    }
    return repository.markSupervisedCheckOk(requesterNodeId.trim(), at);
  }

  @Override
  @Transactional
  public int markSupervisedCheckFailed(final String requesterNodeId, final Instant at) {
    if (requesterNodeId == null || requesterNodeId.isBlank() || at == null) {
      return 0;
    }
    return repository.markSupervisedCheckFailed(requesterNodeId.trim(), at);
  }

  @Override
  @Transactional(readOnly = true)
  public List<String> listSupervisedNodeIds() {
    return repository.findDistinctRequesterNodeIds();
  }

  private RecoveryFileManifestJpaEntity toEntity(final CustodiedFileManifest manifest) {
    final RecoveryFileManifestJpaEntity entity = new RecoveryFileManifestJpaEntity();
    entity.setFileId(manifest.fileId());
    entity.setRequesterNodeId(manifest.requesterNodeId());
    entity.setRequesterPublicKey(manifest.requesterPublicKey());
    entity.setDirectoryPath(manifest.directoryPath());
    entity.setOriginalFileName(manifest.originalFileName());
    entity.setOriginalFileHash(manifest.originalFileHash());
    entity.setOriginalSizeBytes(manifest.originalSizeBytes());
    entity.setCompressedSizeBytes(manifest.compressedSizeBytes());
    entity.setCompressionAlgorithm(manifest.compressionAlgorithm());
    entity.setRedundancyN(manifest.redundancyN());
    entity.setRedundancyK(manifest.redundancyK());
    entity.setClientPlacementsJson(manifest.clientPlacementsJson());

    // El adapter sintetiza blocks_json para legacy single-block
    // Preserva los hashes flat sin necesidad de columnas derivables. multi_block discrimina
    // el origen del JSON al hidratar.
    final boolean multiBlock =
        manifest.clientBlocksJson() != null && !manifest.clientBlocksJson().isBlank();
    entity.setMultiBlock(multiBlock);
    if (multiBlock) {
      entity.setClientBlocksJson(manifest.clientBlocksJson());
    } else {
      entity.setClientBlocksJson(synthesizeLegacyBlocksJson(manifest));
    }

    entity.setStoredAt(manifest.storedAt());
    entity.setLastSupervisedCheckAt(manifest.lastSupervisedCheckAt());
    entity.setConsecutiveOriginFailures(manifest.consecutiveOriginFailures());
    return entity;
  }

  private CustodiedFileManifest toDomain(final RecoveryFileManifestJpaEntity entity) {
    final List<Map<String, Object>> blocks = readBlocks(entity.getClientBlocksJson());
    if (blocks.isEmpty()) {
      throw new IllegalStateException(
          "client_blocks_json missing for fileId=" + entity.getFileId());
    }

    final List<String> flatHashes = new ArrayList<>();
    long firstBlockSize = 0L;
    for (int i = 0; i < blocks.size(); i++) {
      final Map<String, Object> block = blocks.get(i);
      @SuppressWarnings("unchecked")
      final List<String> blockHashes = (List<String>) block.get("fragmentHashes");
      if (blockHashes == null) {
        throw new IllegalStateException(
            "block " + i + " has no fragmentHashes for fileId=" + entity.getFileId());
      }
      flatHashes.addAll(blockHashes);
      if (i == 0) {
        final Object size = block.get("blockSizeBytes");
        if (size instanceof Number num) {
          firstBlockSize = num.longValue();
        }
      }
    }

    final int redundancyK = entity.getRedundancyK();
    final long fragmentSize = firstBlockSize / redundancyK;
    final int fragmentCount = flatHashes.size();

    final String wireBlocksJson = entity.isMultiBlock() ? entity.getClientBlocksJson() : null;

    return new CustodiedFileManifest(
        entity.getFileId(),
        entity.getRequesterNodeId(),
        entity.getRequesterPublicKey(),
        entity.getDirectoryPath(),
        entity.getOriginalFileName(),
        entity.getOriginalFileHash(),
        entity.getOriginalSizeBytes(),
        entity.getCompressedSizeBytes(),
        entity.getCompressionAlgorithm(),
        fragmentCount,
        fragmentSize,
        entity.getRedundancyN(),
        entity.getRedundancyK(),
        flatHashes,
        entity.getClientPlacementsJson(),
        wireBlocksJson,
        entity.getStoredAt(),
        entity.getLastSupervisedCheckAt(),
        entity.getConsecutiveOriginFailures());
  }

  /**
   * Sintetiza un bloque virtual legacy desde el manifest single-block para persistirlo en {@code
   * client_blocks_json}. El bloque sintético tiene {@code blockIndex=0}, {@code
   * blockSizeBytes=originalSizeBytes}, {@code blockHash=originalFileHash}, {@code
   * fragmentHashes=manifest.fragmentHashes()}. Garantiza que el blob JSON nunca sea null, los
   * hashes flat se reconstruyen al hidratar sin necesidad de columnas derivables.
   */
  private String synthesizeLegacyBlocksJson(final CustodiedFileManifest manifest) {
    final Map<String, Object> syntheticBlock =
        Map.of(
            "blockIndex", 0,
            "blockSizeBytes", manifest.originalSizeBytes(),
            "blockHash", manifest.originalFileHash(),
            "fragmentHashes", manifest.fragmentHashes());
    try {
      return objectMapper.writeValueAsString(List.of(syntheticBlock));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to synthesize legacy blocks_json", ex);
    }
  }

  private List<Map<String, Object>> readBlocks(final String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, BLOCK_LIST_TYPE);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to deserialize client_blocks_json", ex);
    }
  }

  // Reservado por si futuras call sites necesitan parsear hashes JSON ad-hoc (e.g. tests).
  @SuppressWarnings("unused")
  private List<String> readHashesJson(final String json) {
    try {
      return objectMapper.readValue(json, HASH_LIST_TYPE);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to deserialize hashes JSON", ex);
    }
  }
}
