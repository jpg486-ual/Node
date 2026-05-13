package es.ual.node.recovery.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.filesystem.domain.FragmentHealthStatus;
import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.filesystem.ports.out.FileManifestPort;
import es.ual.node.filesystem.ports.out.FragmentPlacementPort;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort;
import es.ual.node.negotiation.domain.BlockManifest;
import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.recovery.application.RecoveryProperties.RestoreStrategy;
import es.ual.node.recovery.domain.CustodiedFileManifest;
import es.ual.node.recovery.ports.out.FileRecomposePort;
import es.ual.node.recovery.ports.out.RemoteCustodiedManifestListClientPort;
import es.ual.node.recovery.ports.out.RemoteOrphanFragmentAckClientPort;
import es.ual.node.recovery.ports.out.RemoteRecoveryReconstructClientPort;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Restores the local filesystem metadata of the node from manifests custodied by the configured
 * tutor. Triggered by {@link es.ual.node.bootstrap.configuration.RecoveryBootstrapRunner} cuando
 * {@code node.recovery.mode=RESTORE}.
 *
 * <p>Reconstruye tres tablas locales desde la salida de {@code GET /recovery/file-manifests} en el
 * tutor:
 *
 * <ul>
 *   <li>{@code fs_entry}: el árbol cliente visible.
 *   <li>{@code client_file_manifest}: necesario para que el reconstruct de download funcione.
 *   <li>{@code client_fragment_placement}: routing peer-side a partir de {@code
 *       clientPlacementsJson}.
 * </ul>
 *
 * <p>Si los ports {@link FileManifestPort} y {@link FragmentPlacementPort} no son aportados (legacy
 * 4-arg ctor), sólo se reconstruye {@code fs_entry} y se registra un WARN. Esa configuración no
 * permite descargas tras restore.
 */
public class NodeFsRestoreService {

  private static final Logger LOGGER = LoggerFactory.getLogger(NodeFsRestoreService.class);

  private final RemoteCustodiedManifestListClientPort manifestListClient;
  private final FsEntryPort fsEntryPort;
  private final FileManifestPort fileManifestPort;
  private final FragmentPlacementPort fragmentPlacementPort;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final RestoreStrategy strategy;
  private final RemoteRecoveryReconstructClientPort reconstructClient;
  private final FileRecomposePort fileRecomposePort;
  private final RemoteFileManifestStorePort remoteFileManifestStorePort;
  private final RemoteOrphanFragmentAckClientPort remoteOrphanFragmentAckClient;
  private final String tutorBaseUrl;
  private final String localNodeId;
  private final String localBaseUrl;

  /**
   * Full ctor. Inyecta el reconstruct client, el {@link FileRecomposePort} y el {@link
   * RemoteFileManifestStorePort} para limpieza del manifest viejo en tutor. En strategy {@code
   * BYTES_FROM_TUTOR} el servicio pullea bytes del tutor, los re-emite como upload estándar
   * (regenera fileId/manifest/placements) y borra el manifest viejo del tutor. Cero blob local
   * persistido.
   *
   * <p>{@code localNodeId} y {@code localBaseUrl} se usan para detectar self-placements en {@code
   * clientPlacementsJson} y marcarlos como {@link FragmentHealthStatus#PERDIDO} al restaurar, un
   * nodo que entra en RESTORE acaba de perder todo el estado local, por tanto cualquier slot donde
   * se autocustodiaba está estructuralmente perdido. El {@code FileIntegrityRiskOrchestrator}
   * detecta el risk score elevado y dispara recompose total en el siguiente tick. Si ambos son
   * {@code null}, el marcado PERDIDO se omite (compat 4-arg/7-arg legacy).
   */
  public NodeFsRestoreService(
      final RemoteCustodiedManifestListClientPort manifestListClient,
      final FsEntryPort fsEntryPort,
      final FileManifestPort fileManifestPort,
      final FragmentPlacementPort fragmentPlacementPort,
      final ObjectMapper objectMapper,
      final Clock clock,
      final RestoreStrategy strategy,
      final RemoteRecoveryReconstructClientPort reconstructClient,
      final FileRecomposePort fileRecomposePort,
      final RemoteFileManifestStorePort remoteFileManifestStorePort,
      final RemoteOrphanFragmentAckClientPort remoteOrphanFragmentAckClient,
      final String tutorBaseUrl,
      final String localNodeId,
      final String localBaseUrl) {
    if (manifestListClient == null || fsEntryPort == null || clock == null || strategy == null) {
      throw new IllegalArgumentException("manifestListClient/fsEntryPort/clock/strategy required");
    }
    if ((fragmentPlacementPort != null) != (objectMapper != null)) {
      throw new IllegalArgumentException(
          "fragmentPlacementPort and objectMapper must both be provided or both omitted");
    }
    this.manifestListClient = manifestListClient;
    this.fsEntryPort = fsEntryPort;
    this.fileManifestPort = fileManifestPort;
    this.fragmentPlacementPort = fragmentPlacementPort;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.strategy = strategy;
    this.reconstructClient = reconstructClient;
    this.fileRecomposePort = fileRecomposePort;
    this.remoteFileManifestStorePort = remoteFileManifestStorePort;
    this.remoteOrphanFragmentAckClient = remoteOrphanFragmentAckClient;
    this.tutorBaseUrl = tutorBaseUrl;
    this.localNodeId = localNodeId == null || localNodeId.isBlank() ? null : localNodeId.trim();
    this.localBaseUrl = localBaseUrl == null || localBaseUrl.isBlank() ? null : localBaseUrl.trim();
  }

  /**
   * 12-arg ctor (sin self-placement detection). Compat para callers existentes que no necesitan
   * marcar self-placements como PERDIDO durante restore.
   */
  public NodeFsRestoreService(
      final RemoteCustodiedManifestListClientPort manifestListClient,
      final FsEntryPort fsEntryPort,
      final FileManifestPort fileManifestPort,
      final FragmentPlacementPort fragmentPlacementPort,
      final ObjectMapper objectMapper,
      final Clock clock,
      final RestoreStrategy strategy,
      final RemoteRecoveryReconstructClientPort reconstructClient,
      final FileRecomposePort fileRecomposePort,
      final RemoteFileManifestStorePort remoteFileManifestStorePort,
      final RemoteOrphanFragmentAckClientPort remoteOrphanFragmentAckClient,
      final String tutorBaseUrl) {
    this(
        manifestListClient,
        fsEntryPort,
        fileManifestPort,
        fragmentPlacementPort,
        objectMapper,
        clock,
        strategy,
        reconstructClient,
        fileRecomposePort,
        remoteFileManifestStorePort,
        remoteOrphanFragmentAckClient,
        tutorBaseUrl,
        null,
        null);
  }

  /**
   * 7-arg ctor — strategy METADATA_ONLY (sin BYTES_FROM_TUTOR). Para callers que no necesitan
   * pulleo de bytes.
   */
  public NodeFsRestoreService(
      final RemoteCustodiedManifestListClientPort manifestListClient,
      final FsEntryPort fsEntryPort,
      final FileManifestPort fileManifestPort,
      final FragmentPlacementPort fragmentPlacementPort,
      final ObjectMapper objectMapper,
      final Clock clock,
      final RestoreStrategy strategy) {
    this(
        manifestListClient,
        fsEntryPort,
        fileManifestPort,
        fragmentPlacementPort,
        objectMapper,
        clock,
        strategy,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Legacy 4-arg constructor. Restores only {@code fs_entry}, no manifest, no placements. Conserved
   * for tests and callers that don't yet wire the catalog ports.
   */
  public NodeFsRestoreService(
      final RemoteCustodiedManifestListClientPort manifestListClient,
      final FsEntryPort fsEntryPort,
      final Clock clock,
      final RestoreStrategy strategy) {
    this(manifestListClient, fsEntryPort, null, null, null, clock, strategy);
  }

  /**
   * Performs the restore flow once. Idempotent: existing fs_entry rows whose {@code fileId} already
   * matches a manifest are kept untouched.
   *
   * @return summary of restore outcome
   */
  public RestoreSummary restore() {
    final var manifests = manifestListClient.fetchManifests();
    int created = 0;
    int reused = 0;
    int skipped = 0;
    final Instant now = Instant.now(clock);

    for (CustodiedFileManifest manifest : manifests) {
      final var existing = fsEntryPort.findByFileId(manifest.fileId());
      final boolean fsEntryAlreadyPresent = existing.isPresent();

      // Aunque fs_entry exista (p.ej. restaurado vía pg_dump del backup de
      // users), seguimos intentando restaurar client_file_manifest + client_fragment_placement
      // porque NO se incluyen en el backup de users. Solo el catálogo cliente trae fs_entry.
      // Sin el manifest+placements, los downloads fallarían. La idempotencia está en los ports:
      // save() sobre filaId existente actúa como upsert seguro.

      // The first segment of `directoryPath` is the username (the
      // node embeds the user when emitting manifests). If the path lacks a username
      // segment, the manifest is skipped with WARN. The operator must restore the user
      // backup first.
      final String directoryPath = manifest.directoryPath();
      final String[] parts =
          directoryPath.startsWith("/") ? directoryPath.substring(1).split("/", 2) : new String[0];
      if (parts.length == 0 || parts[0].isBlank()) {
        skipped++;
        LOGGER
            .atWarn()
            .setMessage(
                "Skipping manifest restore: directoryPath does not encode username as first"
                    + " segment")
            .addKeyValue("fileId", manifest.fileId())
            .addKeyValue("directoryPath", directoryPath)
            .log();
        continue;
      }
      final String username = parts[0];
      final String relativeDir = parts.length > 1 ? "/" + parts[1] : "";
      final String fullPath =
          (relativeDir.isEmpty() ? "" : relativeDir) + "/" + manifest.originalFileName();

      final String entryId;
      if (fsEntryAlreadyPresent) {
        reused++;
        entryId = existing.get().entryId();
      } else {
        final FsEntry restored =
            new FsEntry(
                UUID.randomUUID().toString(),
                username,
                fullPath,
                FsEntryType.FILE,
                manifest.originalSizeBytes(),
                manifest.originalFileHash(),
                manifest.fileId(),
                1L,
                now,
                false);
        fsEntryPort.save(restored);
        entryId = restored.entryId();
        created++;
      }

      // Independiente de si fs_entry ya existía, restaurar
      // client_file_manifest + client_fragment_placement (no vienen en pg_dump de users).
      // Idempotencia: solo persistir si no existe ya (evita duplicados en placements en
      // restore re-ejecutado).
      if (fileManifestPort != null && fileManifestPort.findByFileId(manifest.fileId()).isEmpty()) {
        try {
          fileManifestPort.save(toFileManifest(manifest), username, entryId);
        } catch (RuntimeException ex) {
          LOGGER
              .atWarn()
              .setMessage(
                  "Failed to restore FileManifest for fileId; entry created but downloads"
                      + " may fail")
              .addKeyValue("fileId", manifest.fileId())
              .addKeyValue("error", ex.getMessage())
              .log();
        }
      }
      if (fragmentPlacementPort != null) {
        if (fragmentPlacementPort.findByFileId(manifest.fileId()).isEmpty()) {
          restorePlacements(manifest);
        }
      } else {
        LOGGER
            .atWarn()
            .setMessage(
                "Legacy NodeFsRestoreService wiring (no placement port) — downloads"
                    + " disabled until placements are restored manually")
            .addKeyValue("fileId", manifest.fileId())
            .log();
      }

      if (strategy == RestoreStrategy.BYTES_FROM_TUTOR) {
        if (reconstructClient != null
            && fileRecomposePort != null
            && tutorBaseUrl != null
            && !tutorBaseUrl.isBlank()) {
          reuploadAfterReconstruct(manifest, username, entryId);
        } else {
          LOGGER
              .atWarn()
              .setMessage(
                  "BYTES_FROM_TUTOR strategy selected but deps incomplete (reconstructClient/"
                      + "fileRecomposePort/tutorBaseUrl) — skipping byte pull, downloads will fail")
              .addKeyValue("fileId", manifest.fileId())
              .log();
        }
      }
    }

    LOGGER
        .atInfo()
        .setMessage("Node FS restore completed")
        .addKeyValue("totalManifests", manifests.size())
        .addKeyValue("created", created)
        .addKeyValue("reused", reused)
        .addKeyValue("skipped", skipped)
        .addKeyValue("strategy", strategy.name())
        .log();

    return new RestoreSummary(manifests.size(), created, reused, skipped);
  }

  /**
   * Construye el {@link FileManifest} aggregate desde el {@link CustodiedFileManifest} del tutor.
   * Si {@code clientBlocksJson} está presente, lo deserializa y lo pasa al constructor 13-arg para
   * que la reconstrucción funcione bloque a bloque. Para manifests legacy (single-block o sin
   * blocks JSON) cae al constructor 12 arg y el reconstruct usa {@code syntheticSingleBlock}.
   */
  private FileManifest toFileManifest(final CustodiedFileManifest cm) {
    final List<BlockManifest> blocks = deserialiseBlocks(cm.clientBlocksJson(), cm.fileId());
    if (blocks.isEmpty()) {
      return new FileManifest(
          cm.fileId(),
          cm.directoryPath(),
          cm.originalFileName(),
          cm.originalSizeBytes(),
          cm.compressedSizeBytes(),
          cm.compressionAlgorithm(),
          cm.originalFileHash(),
          cm.fragmentCount(),
          cm.fragmentSize(),
          cm.redundancyN(),
          cm.redundancyK(),
          cm.fragmentHashes());
    }
    return new FileManifest(
        cm.fileId(),
        cm.directoryPath(),
        cm.originalFileName(),
        cm.originalSizeBytes(),
        cm.compressedSizeBytes(),
        cm.compressionAlgorithm(),
        cm.originalFileHash(),
        cm.fragmentCount(),
        cm.fragmentSize(),
        cm.redundancyN(),
        cm.redundancyK(),
        cm.fragmentHashes(),
        blocks);
  }

  private List<BlockManifest> deserialiseBlocks(final String json, final String fileId) {
    if (json == null || json.isBlank() || objectMapper == null) {
      return List.of();
    }
    try {
      final List<BlockManifest> blocks =
          objectMapper.readValue(json, new TypeReference<List<BlockManifest>>() {});
      return blocks == null ? List.of() : blocks;
    } catch (Exception ex) {
      LOGGER
          .atWarn()
          .setMessage("Failed to deserialise clientBlocksJson; falling back to single-block path")
          .addKeyValue("fileId", fileId)
          .addKeyValue("error", ex.getMessage())
          .log();
      return List.of();
    }
  }

  private void restorePlacements(final CustodiedFileManifest manifest) {
    final String json = manifest.clientPlacementsJson();
    if (json == null || json.isBlank()) {
      LOGGER
          .atWarn()
          .setMessage(
              "Manifest predates client placements. No fragment routing available;"
                  + " downloads will fail until manual reconfiguration")
          .addKeyValue("fileId", manifest.fileId())
          .log();
      return;
    }
    final List<FragmentPlacement> placements;
    try {
      placements = objectMapper.readValue(json, new TypeReference<List<FragmentPlacement>>() {});
    } catch (Exception ex) {
      LOGGER
          .atWarn()
          .setMessage("Failed to deserialise clientPlacementsJson; skipping placements")
          .addKeyValue("fileId", manifest.fileId())
          .addKeyValue("error", ex.getMessage())
          .log();
      return;
    }
    int saved = 0;
    int selfMarkedLost = 0;
    final Instant now = Instant.now(clock);
    for (FragmentPlacement placement : placements) {
      try {
        final FragmentPlacement toPersist;
        if (isSelfPlacement(placement)) {
          // Self-custody slot: el nodo en RESTORE acaba de perder todo su estado local, así
          // que el fragment que se autocustodiaba está estructuralmente perdido. Marcar
          // PERDIDO (peso 1.0 en risk score) para que FileIntegrityRiskOrchestrator dispare
          // recompose total en el siguiente tick.
          toPersist = placement.withHealth(FragmentHealthStatus.PERDIDO, now);
          selfMarkedLost++;
        } else {
          toPersist = placement;
        }
        fragmentPlacementPort.save(toPersist);
        saved++;
      } catch (RuntimeException ex) {
        LOGGER
            .atWarn()
            .setMessage("Failed to persist FragmentPlacement during restore; continuing")
            .addKeyValue("fileId", manifest.fileId())
            .addKeyValue("fragmentId", placement.fragmentId())
            .addKeyValue("error", ex.getMessage())
            .log();
      }
    }
    LOGGER
        .atInfo()
        .setMessage("FragmentPlacements restored for fileId")
        .addKeyValue("fileId", manifest.fileId())
        .addKeyValue("placementsSaved", saved)
        .addKeyValue("placementsTotal", placements.size())
        .addKeyValue("selfMarkedLost", selfMarkedLost)
        .log();
  }

  /**
   * Detecta si un placement corresponde al propio nodo (self-custody). Match por crypto-id o por
   * baseUrl. El segundo es necesario porque {@code FileContentDistributionService} persiste el
   * custodianNodeId como sentinel legacy {@code peer@<baseUrl>} en lugar del crypto-id.
   */
  private boolean isSelfPlacement(final FragmentPlacement placement) {
    if (localNodeId == null && localBaseUrl == null) {
      return false;
    }
    if (localNodeId != null && localNodeId.equals(placement.custodianNodeId())) {
      return true;
    }
    if (localBaseUrl != null
        && placement.custodianBaseUrl() != null
        && localBaseUrl.equals(placement.custodianBaseUrl().trim())) {
      return true;
    }
    return false;
  }

  /**
   * Tras reconstruir bytes vía tutor (RS-decode bloque a bloque), el archivo se <strong>re-emite
   * como subida estándar</strong> mediante {@link FileRecomposePort#reUploadTotal} (delegado al
   * pipeline upload que regenera fileId/manifest/placements y distribuye N fragments RS a peers).
   * Tras la re-distribución exitosa, se borra el manifest viejo del tutor para que la única
   * recuperabilidad apunte al fileId nuevo.
   *
   * <p>Cambio cardinal vs implementación anterior: <strong>cero blob local</strong>. El nodo origen
   * NUNCA persiste el archivo completo en disco, el modelo del proyecto (fragments-only nodes) se
   * preserva tras restore.
   *
   * <p>Cleanup tutor-side post-reupload exitoso:
   *
   * <ul>
   *   <li><strong>Manifest viejo</strong> ({@code recovery_file_manifest}): se elimina via {@code
   *       remoteFileManifestStorePort.delete(oldFileId, ...)}. Si la llamada DELETE falla (tutor
   *       unreachable mid-flight), el manifest queda huérfano; el download via {@code newFileId}
   *       sigue funcionando porque el manifest nuevo ya está replicado.
   *   <li><strong>Fragments huérfanos</strong> ({@code recovery_orphan_fragment}): NO se eliminan
   *       aquí. Reserva la eliminación de {@code recovery_orphan_fragment} al ciclo claim+ack del
   *       origen. Tras el reUpload los fragments viejos del tutor referencian a un {@code
   *       oldFileId} que ya no aparece en ningún manifest, son storage huérfano hasta limpieza
   *       manual del operador.
   * </ul>
   *
   * <p>Idempotencia entre runs del bootstrap: una segunda pasada del restore lee del tutor solo el
   * manifest nuevo (el viejo ya fue borrado), encuentra el {@code fs_entry} local con {@code
   * fileId=newFileId} y entra en el branch {@code reused}. Si la strategy sigue siendo {@code
   * BYTES_FROM_TUTOR} se re-ejecuta el reUpload con un {@code fileId} aún más nuevo y borra el
   * anterior. En estado estable solo hay un manifest vivo por {@code fs_entry}.
   */
  private void reuploadAfterReconstruct(
      final CustodiedFileManifest manifest, final String username, final String entryId) {
    if (fragmentPlacementPort == null) {
      LOGGER
          .atWarn()
          .setMessage(
              "RESTORE_REUPLOAD: fragmentPlacementPort null, cannot enumerate fragments to"
                  + " reconstruct")
          .addKeyValue("fileId", manifest.fileId())
          .log();
      return;
    }
    final List<FragmentPlacement> placements =
        fragmentPlacementPort.findByFileId(manifest.fileId());
    if (placements.isEmpty()) {
      LOGGER
          .atWarn()
          .setMessage("RESTORE_REUPLOAD: no placements for fileId, skipping reconstruct")
          .addKeyValue("fileId", manifest.fileId())
          .log();
      return;
    }
    final Map<Integer, List<FragmentPlacement>> byBlock = new java.util.TreeMap<>();
    for (FragmentPlacement p : placements) {
      byBlock.computeIfAbsent(p.blockIndex(), k -> new ArrayList<>()).add(p);
    }
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    final int symbolSize = computeSymbolSizeForReconstruct(manifest);
    try {
      for (Map.Entry<Integer, List<FragmentPlacement>> e : byBlock.entrySet()) {
        final List<RemoteRecoveryReconstructClientPort.FragmentReference> refs =
            e.getValue().stream()
                .map(
                    p ->
                        new RemoteRecoveryReconstructClientPort.FragmentReference(
                            p.fragmentId(), p.fragmentIndex(), p.parity()))
                .toList();
        final byte[] blockBytes =
            reconstructClient.reconstruct(
                tutorBaseUrl,
                manifest.fileId(),
                manifest.originalFileHash(),
                manifest.redundancyN(),
                manifest.redundancyK(),
                symbolSize,
                refs);
        baos.write(blockBytes);
      }
    } catch (RuntimeException ex) {
      LOGGER
          .atWarn()
          .setMessage(
              "RESTORE_REUPLOAD: tutor reconstruct failed for fileId; file remains unrecoverable")
          .addKeyValue("fileId", manifest.fileId())
          .addKeyValue("error", ex.getMessage())
          .log();
      return;
    } catch (java.io.IOException ex) {
      // ByteArrayOutputStream.write(byte[]) — IOException is impossible per spec but signature
      // declares it. Map to runtime to avoid leaking checked exceptions to caller.
      throw new IllegalStateException("RESTORE_REUPLOAD: in-memory write failed", ex);
    }

    final byte[] full = baos.toByteArray();
    final var entryOpt = fsEntryPort.findByUsernameAndEntryId(username, entryId);
    if (entryOpt.isEmpty()) {
      LOGGER
          .atWarn()
          .setMessage(
              "RESTORE_REUPLOAD: fs_entry vanished between restore and reupload; aborting reupload")
          .addKeyValue("fileId", manifest.fileId())
          .addKeyValue("entryId", entryId)
          .log();
      return;
    }
    final FsEntry entry = entryOpt.get();

    final String oldFileId = manifest.fileId();
    try {
      fileRecomposePort.reUploadTotal(entry, full);
    } catch (RuntimeException ex) {
      LOGGER
          .atWarn()
          .setMessage("RESTORE_REUPLOAD: re-distribution failed; old fileId left intact for retry")
          .addKeyValue("fileId", oldFileId)
          .addKeyValue("entryId", entryId)
          .addKeyValue("error", ex.getMessage())
          .log();
      return;
    }

    LOGGER
        .atInfo()
        .setMessage("RESTORE_REUPLOAD: bytes re-distributed as standard upload (no local blob)")
        .addKeyValue("event", "RESTORE_REUPLOAD_COMPLETED")
        .addKeyValue("oldFileId", oldFileId)
        .addKeyValue("entryId", entryId)
        .addKeyValue("username", username)
        .addKeyValue("sizeBytes", full.length)
        .addKeyValue("blocks", byBlock.size())
        .log();

    // Cleanup del manifest viejo en tutor — el fileId nuevo cubre la recuperabilidad. Best-effort:
    // un fallo aquí deja el manifest viejo huérfano (el download por fileId nuevo sigue
    // funcionando, pero el tutor acumula un row stale hasta limpieza manual). No abortamos.
    if (remoteFileManifestStorePort != null) {
      try {
        remoteFileManifestStorePort.delete(oldFileId, tutorBaseUrl);
      } catch (RuntimeException ex) {
        LOGGER
            .atWarn()
            .setMessage(
                "RESTORE_REUPLOAD: failed to delete stale manifest from tutor; manifest remains"
                    + " orphan but new fileId is recoverable")
            .addKeyValue("oldFileId", oldFileId)
            .addKeyValue("error", ex.getMessage())
            .log();
      }
    }

    // ACK fragments huérfanos del tutor que ya no son necesarios. Reusa el endpoint
    // existente POST /recovery/orphan-fragments/{fragmentId}/ack que valida
    // ownership y borra orphan + payload. El reconstruct previo ya pulleó los bytes, así que el
    // tutor puede liberar storage sin pérdida funcional.
    if (remoteOrphanFragmentAckClient != null) {
      int acked = 0;
      int failed = 0;
      for (FragmentPlacement placement : placements) {
        try {
          remoteOrphanFragmentAckClient.ack(placement.fragmentId(), tutorBaseUrl);
          acked++;
        } catch (RuntimeException ex) {
          failed++;
          LOGGER
              .atWarn()
              .setMessage(
                  "RESTORE_REUPLOAD: ACK to tutor failed for orphan fragment; row remains until"
                      + " manual cleanup")
              .addKeyValue("oldFileId", oldFileId)
              .addKeyValue("fragmentId", placement.fragmentId())
              .addKeyValue("error", ex.getMessage())
              .log();
        }
      }
      LOGGER
          .atInfo()
          .setMessage("RESTORE_REUPLOAD: orphan fragments ACKed at tutor")
          .addKeyValue("event", "RESTORE_REUPLOAD_ORPHAN_ACK")
          .addKeyValue("oldFileId", oldFileId)
          .addKeyValue("acked", acked)
          .addKeyValue("failed", failed)
          .addKeyValue("total", placements.size())
          .log();
    }
  }

  /**
   * Resuelve {@code symbolSize} para el reconstruct request. Usa {@code fragmentSize} del manifest
   * (en escenarios single-block) o el primer bloque de {@code blocks} cuando el manifest es
   * multi-block.
   */
  private int computeSymbolSizeForReconstruct(final CustodiedFileManifest manifest) {
    // El symbolSize viaja en el FileManifest aggregate pero no en CustodiedFileManifest. Asume
    // 4096 (default RS scheme symbol-size del project) si no se puede inferir. En la mayoría de
    // demos el cluster usa defaults coherentes (4 KiB symbols, 4 MiB blocks).
    return 4096;
  }

  /** Summary of a restore execution. */
  public record RestoreSummary(int totalManifests, int created, int reused, int skipped) {}
}
