package es.ual.node.filesystem.application;

import es.ual.node.discovery.application.DiscoveryQueryCache;
import es.ual.node.discovery.application.DiscoveryUnreachableException;
import es.ual.node.discovery.domain.DiscoveryRequest;
import es.ual.node.discovery.domain.DiscoveryResponse;
import es.ual.node.discovery.ports.out.RemoteDiscoveryQueryClientPort;
import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.ports.out.FileManifestPort;
import es.ual.node.filesystem.ports.out.FragmentPlacementPort;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort;
import es.ual.node.filesystem.ports.out.RemoteFragmentDistributionClientPort;
import es.ual.node.negotiation.domain.BlockManifest;
import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.reedsolomon.domain.RsFragment;
import es.ual.node.reedsolomon.domain.RsScheme;
import es.ual.node.reedsolomon.ports.out.RsDecoderPort;
import es.ual.node.reedsolomon.ports.out.RsEncoderPort;
import es.ual.node.reedsolomon.ports.out.RsIntegrityVerifierPort;
import es.ual.node.userregistration.ports.out.UserQuotaPort;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the upload and download of user file content over the distributed Reed-Solomon
 * pipeline.
 *
 * <p>Streaming end-to-end with RS por bloques. The file is read from the inbound stream in
 * fixed-size blocks ({@code blockSizeBytes}, default 4 MB); each block produces its own n fragments
 * via the RS codec, distributed sequentially to custodians, and recorded as one entry in {@link
 * FileManifest#blocks()}. Peak RAM no longer scales with file size, only with {@code blockSizeBytes
 * × n × concurrent_uploads}. The legacy {@code byte[]} entry point delegates to the streaming path
 * (wrapping the array in a {@link ByteArrayInputStream}) so existing tests keep working without
 * code change; for inputs ≤ {@code blockSizeBytes} it produces a manifest with exactly one block.
 *
 * <p>Upload flow ({@link #distributeUploadStreaming}):
 *
 * <ol>
 *   <li>Reserve quota = {@code contentLength × n / k} bytes against the user's allowance.
 *   <li>Loop the input stream in {@code blockSizeBytes} chunks; per block: SHA-256 incrementally
 *       (block + global), RS-encode the block bytes, signed POST each fragment to the custodians,
 *       accumulate one {@link BlockManifest} entry and its placements.
 *   <li>Validate global SHA-256 against {@link FsEntry#checksum()} (if present).
 *   <li>Persist {@link FileManifest} (with full {@code blocks[]}) + placements locally.
 *   <li>On any failure release the reserved quota in the {@code finally} block.
 * </ol>
 *
 * <p>Download flow ({@link #reconstructDownload}): unchanged in this subtask; the per-block
 * download streaming path lands in the next subtask.
 *
 * <p>Quota release on delete is exposed via {@link #releaseQuotaForFile} for {@code
 * FileSystemService.delete()} to invoke.
 */
public class FileContentDistributionService {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(FileContentDistributionService.class);

  private final RsEncoderPort encoderPort;
  private final RsDecoderPort decoderPort;

  /**
   * Kept as injected dependency for binary/wiring compatibility. The streaming reconstruction path
   * verifies integrity inline via {@link MessageDigest} per block and globally, so this port is not
   * invoked anymore. Future history (compressed/transformed pipelines) may reuse it.
   */
  @SuppressWarnings("unused")
  private final RsIntegrityVerifierPort integrityVerifierPort;

  private final FileManifestPort fileManifestPort;
  private final FragmentPlacementPort fragmentPlacementPort;
  private final RemoteFragmentDistributionClientPort remoteFragmentClient;
  private final UserQuotaPort userQuotaPort;
  private final Clock clock;
  private final RsScheme defaultScheme;
  private final long maxContentBytes;
  private final int blockSizeBytes;
  private final List<String> custodianBaseUrls;
  // Replicates manifest+placements to the requester's tutor sync after
  // distribution. Optional dependencies, when null/blank the orchestrator skips replication
  // (legacy behavior, used by tests that don't need the recovery anchor).
  private final RemoteFileManifestStorePort remoteFileManifestStorePort;
  private final String tutorBaseUrl;
  // Al final del pipeline, marcar el FsEntry como contentUploaded=true
  // para que aparezca en listings (/fs/tree). Optional null en tests legacy que no necesitan
  // razonar sobre visibilidad.
  private final FsEntryPort fsEntryPort;
  // Dynamic Discovery wiring. When non-null, the orchestrator queries DiscoveryService
  // per upload (with an internal TTL cache).
  private final DiscoveryWiring discoveryWiring;

  /**
   * Creates orchestrator with the legacy single-shot block size (block size equals the configured
   * cap so any file fits in one block). Kept for tests and minimal wiring.
   */
  public FileContentDistributionService(
      final RsEncoderPort encoderPort,
      final RsDecoderPort decoderPort,
      final RsIntegrityVerifierPort integrityVerifierPort,
      final FileManifestPort fileManifestPort,
      final FragmentPlacementPort fragmentPlacementPort,
      final RemoteFragmentDistributionClientPort remoteFragmentClient,
      final UserQuotaPort userQuotaPort,
      final Clock clock,
      final RsScheme defaultScheme,
      final long maxContentBytes,
      final List<String> custodianBaseUrls) {
    this(
        encoderPort,
        decoderPort,
        integrityVerifierPort,
        fileManifestPort,
        fragmentPlacementPort,
        remoteFragmentClient,
        userQuotaPort,
        clock,
        defaultScheme,
        maxContentBytes,
        // Legacy: single-shot block big enough to hold the whole file. Capped at Integer.MAX_VALUE.
        // When maxContentBytes < 0 (no cap), use Integer.MAX_VALUE so any single file fits in 1
        // block, this constructor is only kept for tests/minimal wiring; production uses the new
        // constructor with an explicit blockSizeBytes (default 4 MB).
        maxContentBytes < 0
            ? Integer.MAX_VALUE
            : (int) Math.min(maxContentBytes, (long) Integer.MAX_VALUE),
        custodianBaseUrls);
  }

  /** Creates orchestrator (No tutor manifest replication). */
  public FileContentDistributionService(
      final RsEncoderPort encoderPort,
      final RsDecoderPort decoderPort,
      final RsIntegrityVerifierPort integrityVerifierPort,
      final FileManifestPort fileManifestPort,
      final FragmentPlacementPort fragmentPlacementPort,
      final RemoteFragmentDistributionClientPort remoteFragmentClient,
      final UserQuotaPort userQuotaPort,
      final Clock clock,
      final RsScheme defaultScheme,
      final long maxContentBytes,
      final int blockSizeBytes,
      final List<String> custodianBaseUrls) {
    this(
        encoderPort,
        decoderPort,
        integrityVerifierPort,
        fileManifestPort,
        fragmentPlacementPort,
        remoteFragmentClient,
        userQuotaPort,
        clock,
        defaultScheme,
        maxContentBytes,
        blockSizeBytes,
        custodianBaseUrls,
        null,
        null,
        null);
  }

  /**
   * Creates orchestrator withwiring (tutor manifest replication) but without the lazy visibility
   * hook. Delegates with {@code fsEntryPort=null}.
   */
  public FileContentDistributionService(
      final RsEncoderPort encoderPort,
      final RsDecoderPort decoderPort,
      final RsIntegrityVerifierPort integrityVerifierPort,
      final FileManifestPort fileManifestPort,
      final FragmentPlacementPort fragmentPlacementPort,
      final RemoteFragmentDistributionClientPort remoteFragmentClient,
      final UserQuotaPort userQuotaPort,
      final Clock clock,
      final RsScheme defaultScheme,
      final long maxContentBytes,
      final int blockSizeBytes,
      final List<String> custodianBaseUrls,
      final RemoteFileManifestStorePort remoteFileManifestStorePort,
      final String tutorBaseUrl) {
    this(
        encoderPort,
        decoderPort,
        integrityVerifierPort,
        fileManifestPort,
        fragmentPlacementPort,
        remoteFragmentClient,
        userQuotaPort,
        clock,
        defaultScheme,
        maxContentBytes,
        blockSizeBytes,
        custodianBaseUrls,
        remoteFileManifestStorePort,
        tutorBaseUrl,
        null);
  }

  /**
   * Creates orchestrator with wiring (tutor replication + lazy visibility) but without the dynamic
   * Discovery integration. Delegates with {@code discoveryWiring=null}.
   */
  public FileContentDistributionService(
      final RsEncoderPort encoderPort,
      final RsDecoderPort decoderPort,
      final RsIntegrityVerifierPort integrityVerifierPort,
      final FileManifestPort fileManifestPort,
      final FragmentPlacementPort fragmentPlacementPort,
      final RemoteFragmentDistributionClientPort remoteFragmentClient,
      final UserQuotaPort userQuotaPort,
      final Clock clock,
      final RsScheme defaultScheme,
      final long maxContentBytes,
      final int blockSizeBytes,
      final List<String> custodianBaseUrls,
      final RemoteFileManifestStorePort remoteFileManifestStorePort,
      final String tutorBaseUrl,
      final FsEntryPort fsEntryPort) {
    this(
        encoderPort,
        decoderPort,
        integrityVerifierPort,
        fileManifestPort,
        fragmentPlacementPort,
        remoteFragmentClient,
        userQuotaPort,
        clock,
        defaultScheme,
        maxContentBytes,
        blockSizeBytes,
        custodianBaseUrls,
        remoteFileManifestStorePort,
        tutorBaseUrl,
        fsEntryPort,
        null);
  }

  /**
   * Creates orchestrator with full wiring AND dynamic Discovery integration. Production constructor
   * wired by {@code FilesystemModuleConfiguration}. When {@code discoveryWiring} is non-null and
   * {@link DiscoveryWiring#remoteQueryClient()} is non-null, the pipeline queries Discovery per
   * upload via signed HTTP {@code POST /ops/discovery/query} (with TTL cache + supernode failover)
   * instead of using the static {@code custodianBaseUrls} list. The static list survives as
   * fallback when Discovery is disabled.
   */
  public FileContentDistributionService(
      final RsEncoderPort encoderPort,
      final RsDecoderPort decoderPort,
      final RsIntegrityVerifierPort integrityVerifierPort,
      final FileManifestPort fileManifestPort,
      final FragmentPlacementPort fragmentPlacementPort,
      final RemoteFragmentDistributionClientPort remoteFragmentClient,
      final UserQuotaPort userQuotaPort,
      final Clock clock,
      final RsScheme defaultScheme,
      final long maxContentBytes,
      final int blockSizeBytes,
      final List<String> custodianBaseUrls,
      final RemoteFileManifestStorePort remoteFileManifestStorePort,
      final String tutorBaseUrl,
      final FsEntryPort fsEntryPort,
      final DiscoveryWiring discoveryWiring) {
    if (encoderPort == null
        || decoderPort == null
        || integrityVerifierPort == null
        || fileManifestPort == null
        || fragmentPlacementPort == null
        || remoteFragmentClient == null
        || userQuotaPort == null
        || clock == null
        || defaultScheme == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    if (maxContentBytes == 0) {
      throw new IllegalArgumentException(
          "maxContentBytes must not be zero (use negative for no cap)");
    }
    if (blockSizeBytes <= 0) {
      throw new IllegalArgumentException("blockSizeBytes must be greater than zero");
    }
    this.encoderPort = encoderPort;
    this.decoderPort = decoderPort;
    this.integrityVerifierPort = integrityVerifierPort;
    this.fileManifestPort = fileManifestPort;
    this.fragmentPlacementPort = fragmentPlacementPort;
    this.remoteFragmentClient = remoteFragmentClient;
    this.userQuotaPort = userQuotaPort;
    this.clock = clock;
    this.defaultScheme = defaultScheme;
    this.maxContentBytes = maxContentBytes;
    this.blockSizeBytes = blockSizeBytes;
    this.custodianBaseUrls = custodianBaseUrls == null ? List.of() : List.copyOf(custodianBaseUrls);
    this.remoteFileManifestStorePort = remoteFileManifestStorePort;
    this.tutorBaseUrl = tutorBaseUrl == null ? null : tutorBaseUrl.trim();
    this.fsEntryPort = fsEntryPort;
    this.discoveryWiring = discoveryWiring;
  }

  /**
   * Wiring for dynamic Discovery integration. El origen consulta supernodos remotos vía {@code
   * RemoteDiscoveryQueryClientPort} con failover round-robin sobre {@code discoverySupernodes}.
   * {@code discoveryQueryCache} sigue cacheando la respuesta agregada (TTL 30 s, clave {@code
   * requestSize + plan}, sin incluir supernodeBaseUrl porque el directorio es replicado entre
   * supernodos).
   *
   * @param remoteQueryClient cliente HTTP signed para consultar supernodos; null desactiva dynamic
   *     discovery (fallback al static {@code custodianBaseUrls} legacy)
   * @param discoverySupernodes lista de baseUrls de supernodos discovery (de {@code
   *     node.topology.discoverySupernodes})
   * @param discoveryQueryCache TTL cache; null desactiva caching (cada upload consulta fresh)
   * @param localNodeId local node identifier (campo {@code nodeId} de la discover request)
   * @param localFailureDomain local failure domain (campo {@code failureDomain})
   * @param defaultRequestedBucket default bucket size cuando el upload no trae el propio
   * @param defaultRatio default ratio para expansion de bucket
   * @param defaultDistributionPlan optional {@code zone:count,zone:count} forzado en cada request
   * @param maxDiscoveryRetries cap de retry-with-bigger-request antes de fallar
   * @param allowSelfCandidate cuando {@code false} el origen se filtra del resultado
   */
  public record DiscoveryWiring(
      RemoteDiscoveryQueryClientPort remoteQueryClient,
      List<String> discoverySupernodes,
      DiscoveryQueryCache discoveryQueryCache,
      String localNodeId,
      String localFailureDomain,
      long defaultRequestedBucket,
      double defaultRatio,
      String defaultDistributionPlan,
      int maxDiscoveryRetries,
      boolean allowSelfCandidate) {}

  /**
   * Legacy {@code byte[]} entry point. Wraps the buffer in a {@link ByteArrayInputStream} and
   * delegates to the streaming path. Inputs that fit in a single block (≤ {@code blockSizeBytes})
   * produce a manifest with exactly one block.
   */
  public FileManifest distributeUpload(
      final String username, final FsEntry entry, final byte[] content) {
    if (content == null || content.length == 0) {
      throw new IllegalArgumentException("content must not be empty");
    }
    return distributeUploadStreaming(
        username, entry, new ByteArrayInputStream(content), content.length);
  }

  /**
   * Distributes a user-uploaded binary across the cluster, reading the content from {@code input}
   * in blocks of {@code blockSizeBytes}. The caller MUST pass the exact total length in {@code
   * contentLength}; the orchestrator uses it to reserve quota up front and to detect premature EOF
   * mid-upload.
   *
   * @param username owner username (used for quota accounting)
   * @param entry FS entry the content belongs to
   * @param input binary payload as a stream
   * @param contentLength total length declared by the caller (e.g. {@code
   *     HttpServletRequest.getContentLengthLong()})
   * @return persisted manifest
   */
  public FileManifest distributeUploadStreaming(
      final String username,
      final FsEntry entry,
      final InputStream input,
      final long contentLength) {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    if (entry == null) {
      throw new IllegalArgumentException("entry must not be null");
    }
    if (input == null) {
      throw new IllegalArgumentException("input must not be null");
    }
    if (contentLength <= 0) {
      throw new IllegalArgumentException("contentLength must be greater than zero");
    }
    // MaxContentBytes < 0 means "no application cap", the real limit is RAM peak per
    // upload concurrent ≈ blockSize × n.
    if (maxContentBytes > 0 && contentLength > maxContentBytes) {
      throw new ContentTooLargeException(contentLength, maxContentBytes);
    }
    // Resolve the custodian list once at the start of the upload. Same N peers for
    // every block, so the 1-fragment-per-node-per-file invariant holds at file scope. When
    // Discovery is wired (production), this queries the supernode with retry-with-bigger-request
    // and dedupes by baseUrl. When Discovery is null (legacy/tests), it falls back to the static
    // custodianBaseUrls list and just enforces size >= n.
    final List<String> resolvedCustodianBaseUrls = resolveCustodianBaseUrls(defaultScheme.n());

    final long chargedBytes = (contentLength * defaultScheme.n()) / defaultScheme.k();
    if (!userQuotaPort.tryReserve(username, chargedBytes)) {
      throw new QuotaExceededException(chargedBytes, computeAvailable(username, chargedBytes));
    }

    boolean reservationCommitted = false;
    try {
      // Preflight health check al tutor ANTES de distribuir fragments a peers.
      // Sin esto, si el tutor cae entre el inicio del upload y la replicación del manifest
      // Si el tutor está caído al
      // momento del check, fail-fast con TutorManifestReplicationException → controller mapea a
      // 503 FILESYSTEM_TUTOR_REPLICATION_FAILED.
      if (remoteFileManifestStorePort != null && tutorBaseUrl != null && !tutorBaseUrl.isBlank()) {
        try {
          remoteFileManifestStorePort.checkTutorReachable(tutorBaseUrl);
        } catch (RuntimeException ex) {
          throw new TutorManifestReplicationException(
              "Tutor health check at "
                  + tutorBaseUrl
                  + " failed; upload aborted before any fragment is distributed to peers",
              ex);
        }
      }

      // Re-upload idempotente, siempre generar fileId nuevo. Si existe oldFileId,
      // se limpia localmente más abajo (placements + manifest) y los fragments huérfanos en peers
      // los libera el flujo de probes.
      final String oldFileId =
          (entry.fileId() != null && !entry.fileId().isBlank()) ? entry.fileId() : null;
      final String fileId = UUID.randomUUID().toString();
      final MessageDigest globalDigest = newSha256();
      final List<BlockManifest> blocks = new ArrayList<>();
      final List<FragmentPlacement> placements = new ArrayList<>();
      final List<String> aggregateFragmentHashes = new ArrayList<>();
      int firstFragmentSize = -1;

      long bytesRead = 0L;
      int blockIndex = 0;
      while (bytesRead < contentLength) {
        final long remaining = contentLength - bytesRead;
        final int thisBlockSize = (int) Math.min((long) blockSizeBytes, remaining);
        final byte[] blockBytes = readExactly(input, thisBlockSize);
        if (blockBytes.length != thisBlockSize) {
          throw new IllegalStateException(
              "premature EOF: expected " + thisBlockSize + " bytes, read " + blockBytes.length);
        }
        globalDigest.update(blockBytes);

        final List<RsFragment> fragments = encoderPort.encode(blockBytes, defaultScheme);
        if (fragments.size() != defaultScheme.n()) {
          throw new IllegalStateException(
              "RS encoder returned "
                  + fragments.size()
                  + " fragments, expected "
                  + defaultScheme.n());
        }
        if (firstFragmentSize < 0) {
          firstFragmentSize = fragments.get(0).payloadSize();
        }

        final List<String> blockFragmentHashes = new ArrayList<>(defaultScheme.n());
        for (int i = 0; i < defaultScheme.n(); i++) {
          final RsFragment frag = fragments.get(i);
          final String custodianBaseUrl = resolvedCustodianBaseUrls.get(i);
          final String agreementId = "client-upload-" + UUID.randomUUID();

          // Cierre de race condition upload ↔ keep-list: persistir el placement
          // en el origen ANTES de hacer POST al peer custodian. El CustodianProbeWorker
          // del peer puede correr una ronda en cuanto el fragment aterrice y preguntar
          // al origen "¿este fragment es necesario?". Si el placement no existe aún,
          // el origen contesta `false` y el peer purga el fragment recién recibido.
          // Persistir antes del POST garantiza que cualquier probe inbound subsiguiente
          // encontrará el placement. Si el POST falla, hacemos rollback explícito del
          // placement local (la fila persistida sería un huérfano que confundiría al
          // siguiente probe inbound). El FK físico hacia client_file_manifest fue
          // dropeado en la migración V3 para permitir este ordenamiento; la relación
          // sigue siendo lógica.
          final FragmentPlacement placement =
              new FragmentPlacement(
                  fileId,
                  frag.fragmentId(),
                  blockIndex,
                  frag.index(),
                  frag.isParity(),
                  deriveCustodianNodeId(custodianBaseUrl),
                  custodianBaseUrl,
                  agreementId,
                  frag.checksum(),
                  frag.payloadSize(),
                  clock.instant());
          fragmentPlacementPort.save(placement);

          try {
            remoteFragmentClient.storeFragment(
                custodianBaseUrl,
                frag.fragmentId(),
                agreementId,
                frag.payload(),
                "SHA-256",
                frag.checksum(),
                null);
          } catch (CustodianInsufficientStorageException ex) {
            fragmentPlacementPort.deleteByFileIdAndFragmentId(fileId, frag.fragmentId());
            LOGGER
                .atWarn()
                .setMessage(
                    "Custodian rejected fragment with 507 Insufficient Storage; upload aborted —"
                        + " admin notice: peer running low on disk")
                .addKeyValue("event", "CUSTODIAN_INSUFFICIENT_STORAGE")
                .addKeyValue("severity", "high")
                .addKeyValue("adminNotice", true)
                .addKeyValue("custodianBaseUrl", ex.custodianBaseUrl())
                .addKeyValue("fragmentId", frag.fragmentId())
                .addKeyValue("fileId", fileId)
                .log();
            throw ex;
          } catch (RuntimeException ex) {
            fragmentPlacementPort.deleteByFileIdAndFragmentId(fileId, frag.fragmentId());
            throw ex;
          }

          placements.add(placement);
          blockFragmentHashes.add(frag.checksum());
          aggregateFragmentHashes.add(frag.checksum());
        }

        blocks.add(
            new BlockManifest(
                blockIndex, thisBlockSize, sha256Hex(blockBytes), blockFragmentHashes));
        bytesRead += thisBlockSize;
        blockIndex++;
      }

      if (bytesRead != contentLength) {
        throw new IllegalStateException(
            "byte count mismatch: declared=" + contentLength + " read=" + bytesRead);
      }

      final String originalHash =
          HexFormat.of().formatHex(globalDigest.digest()).toLowerCase(java.util.Locale.ROOT);
      if (entry.checksum() != null
          && !entry.checksum().isBlank()
          && !entry.checksum().equalsIgnoreCase(originalHash)) {
        // Rollback: los placements persistidos en el loop quedarían huérfanos sin manifest.
        fragmentPlacementPort.deleteByFileId(fileId);
        throw new FsContentConflictException(
            "uploaded stream SHA-256 does not match metadata checksum");
      }

      // Prepend username al directoryPath para que el nodo pueda recuperar
      // el ownership en restore (Convención: primer segmento de directoryPath = username).
      // Sin este prefijo, NodeFsRestoreService skip-ea el manifest con WARN porque no puede
      // resolver el dueño del archivo.
      final String parentPath = extractParentPath(entry.path());
      final String userPrefixedDirectoryPath =
          "/" + username + ("/".equals(parentPath) ? "" : parentPath);
      final FileManifest manifest =
          new FileManifest(
              fileId,
              userPrefixedDirectoryPath,
              entry.path() == null
                  ? entry.entryId()
                  : extractFileName(entry.path(), entry.entryId()),
              contentLength,
              null,
              null,
              originalHash,
              blocks.size() * defaultScheme.n(),
              firstFragmentSize,
              defaultScheme.n(),
              defaultScheme.k(),
              aggregateFragmentHashes,
              blocks);

      // Replicate manifest+placements to the tutor BEFORE the local manifest
      // save. Fail-closed semantics: si el tutor está unreachable abortamos el upload sin que
      // quede manifest local. Los placements en `client_fragment_placement` (persistidos uno a
      // uno dentro del loop para cerrar la race con el keep-list inbound) se hacen rollback
      // explícito aquí, sin manifest no deben sobrevivir como huérfanos. Los fragments ya
      // depositados en peers expiran por whitelist probe.
      if (remoteFileManifestStorePort != null && tutorBaseUrl != null && !tutorBaseUrl.isBlank()) {
        try {
          remoteFileManifestStorePort.store(manifest, placements, tutorBaseUrl);
        } catch (RuntimeException ex) {
          fragmentPlacementPort.deleteByFileId(fileId);
          throw new TutorManifestReplicationException(
              "Manifest replication to tutor at " + tutorBaseUrl + " failed; upload aborted", ex);
        }
      }

      // Re-upload cleanup. Borrar placements + manifest del fileId previo para
      // evitar acumulación. Los fragments custodiados en peers se liberan por probe inbound.
      //
      // El log se emite SÓLO si realmente había estado previo que limpiar. En primer upload
      // de un usuario nuevo, `oldFileId` es el placeholder asignado por
      // `FileSystemService.upsertEntry` que nunca llegó a tener placements ni manifest. El
      // delete sería no-op y el log era ruido confuso (parecía un re-upload real cuando no lo
      // era).
      if (oldFileId != null) {
        final int oldPlacements = fragmentPlacementPort.findByFileId(oldFileId).size();
        final boolean oldManifestExists = fileManifestPort.findByFileId(oldFileId).isPresent();
        if (oldPlacements > 0 || oldManifestExists) {
          fragmentPlacementPort.deleteByFileId(oldFileId);
          fileManifestPort.deleteByFileId(oldFileId);
          LOGGER
              .atInfo()
              .setMessage("Re-upload cleanup: previous placements + manifest removed locally")
              .addKeyValue("event", "RE_UPLOAD_CLEANUP")
              .addKeyValue("oldFileId", oldFileId)
              .addKeyValue("newFileId", fileId)
              .addKeyValue("entryId", entry.entryId())
              .addKeyValue("placementsRemoved", oldPlacements)
              .addKeyValue("manifestRemoved", oldManifestExists)
              .log();
        }
      }
      fileManifestPort.save(manifest, username, entry.entryId());
      // Los placements ya se persistieron uno a uno dentro del loop ANTES del POST a cada
      // peer (cierre de race condition keep-list). Ya no se hace bulk save aquí.

      // Marcar el FsEntry como contentUploaded=true para que
      // aparezca en /fs/tree para el propio usuario (otros dispositivos). Hasta este punto
      // el entry estaba oculto en listings (POST /fs lo creó con contentUploaded=false).
      // Además, sincronizar fileId del entry con el fileId nuevo del manifest. Sin
      // esto, el FsEntry quedaría apuntando al oldFileId que acabamos de borrar localmente.
      // Atómico con manifest+placements desde la perspectiva del cliente: si llegamos aquí
      // todo persistió bien. Si fsEntryPort es null (tests legacy), saltamos sin error.
      if (fsEntryPort != null) {
        final FsEntry uploaded = entry.withFileId(fileId).withContentUploaded(true);
        fsEntryPort.save(uploaded);
      }

      reservationCommitted = true;

      LOGGER
          .atInfo()
          .setMessage("File distributed across cluster (streaming, per-block RS)")
          .addKeyValue("username", username)
          .addKeyValue("fileId", fileId)
          .addKeyValue("originalSizeBytes", contentLength)
          .addKeyValue("chargedBytes", chargedBytes)
          .addKeyValue("blocks", blocks.size())
          .addKeyValue("placements", placements.size())
          .log();

      return manifest;
    } finally {
      if (!reservationCommitted) {
        userQuotaPort.release(username, chargedBytes);
      }
    }
  }

  /**
   * Legacy {@code byte[]} download. Accumulates the reconstructed file in memory. Internally
   * delegates to {@link #reconstructDownloadStreaming}; use that variant directly to write straight
   * to the HTTP response without materializing the full byte array in RAM.
   *
   * @param fileId file identifier
   * @return reconstructed binary
   */
  public byte[] reconstructDownload(final String fileId) {
    final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    reconstructDownloadStreaming(fileId, output);
    return output.toByteArray();
  }

  /**
   * Reconstructs the file from custodian fragments and writes the bytes to {@code output} block by
   * block. RAM peak per call is bounded by {@code blockSize × n} regardless of file size: each
   * block is fetched, decoded, integrity-checked and written before the next is touched. Backwards
   * compatible with single-block manifests via {@link #syntheticSingleBlock}.
   *
   * @param fileId file identifier
   * @param output destination for the reconstructed bytes (HTTP response stream typically)
   */
  public void reconstructDownloadStreaming(final String fileId, final java.io.OutputStream output) {
    if (fileId == null || fileId.isBlank()) {
      throw new IllegalArgumentException("fileId must not be blank");
    }
    if (output == null) {
      throw new IllegalArgumentException("output must not be null");
    }

    final FileManifest manifest =
        fileManifestPort
            .findByFileId(fileId.trim())
            .orElseThrow(() -> new NoSuchElementException("manifest not found: " + fileId));
    final List<FragmentPlacement> placements = fragmentPlacementPort.findByFileId(fileId.trim());
    final RsScheme scheme =
        new RsScheme(manifest.redundancyN(), manifest.redundancyK(), (int) manifest.fragmentSize());
    // Placement set must be well-formed (one row per
    // (blockIndex, fragmentIndex)) before paying the network cost of fetching from peers.
    validatePlacementSet(fileId.trim(), placements);

    final List<BlockManifest> blocks =
        manifest.blocks().isEmpty() ? List.of(syntheticSingleBlock(manifest)) : manifest.blocks();

    final MessageDigest globalDigest = newSha256();
    long bytesWritten = 0L;

    for (BlockManifest block : blocks) {
      final List<FragmentPlacement> blockPlacements =
          manifest.blocks().isEmpty()
              ? placements
              : placements.stream().filter(p -> p.blockIndex() == block.blockIndex()).toList();

      final byte[] blockBytes = reconstructBlock(blockPlacements, scheme);
      if (blockBytes.length != block.blockSizeBytes()) {
        throw new IllegalStateException(
            "reconstructed block size mismatch: declared="
                + block.blockSizeBytes()
                + " actual="
                + blockBytes.length);
      }
      if (!sha256Hex(blockBytes).equalsIgnoreCase(block.blockHash())) {
        throw new IllegalStateException(
            "reconstructed block SHA-256 does not match manifest blockHash");
      }
      try {
        output.write(blockBytes);
      } catch (IOException ex) {
        throw new IllegalStateException("I/O error writing reconstructed block to output", ex);
      }
      globalDigest.update(blockBytes);
      bytesWritten += blockBytes.length;
    }

    if (bytesWritten != manifest.originalSizeBytes()) {
      throw new IllegalStateException(
          "reconstructed byte count "
              + bytesWritten
              + " does not match manifest originalSizeBytes "
              + manifest.originalSizeBytes());
    }
    final String reconstructedHash =
        HexFormat.of().formatHex(globalDigest.digest()).toLowerCase(java.util.Locale.ROOT);
    if (!reconstructedHash.equalsIgnoreCase(manifest.originalFileHash())) {
      throw new IllegalStateException("reconstructed bytes do not match manifest hash");
    }
  }

  private byte[] reconstructBlock(
      final List<FragmentPlacement> blockPlacements, final RsScheme scheme) {
    final List<RsFragment> fetched = new ArrayList<>();
    for (FragmentPlacement placement : blockPlacements) {
      try {
        final byte[] payload =
            remoteFragmentClient.fetchFragment(
                placement.custodianBaseUrl(), placement.fragmentId());
        fetched.add(
            new RsFragment(
                placement.fragmentId(),
                placement.fragmentIndex(),
                placement.parity(),
                placement.fragmentChecksum(),
                payload.length,
                payload));
        if (fetched.size() >= scheme.k()) {
          break;
        }
      } catch (RuntimeException exception) {
        LOGGER
            .atWarn()
            .setMessage("Custodian fragment fetch failed; trying next")
            .addKeyValue("custodianBaseUrl", placement.custodianBaseUrl())
            .addKeyValue("fragmentId", placement.fragmentId())
            .addKeyValue("error", exception.getMessage())
            .log();
      }
    }
    if (fetched.size() < scheme.k()) {
      throw new InsufficientCustodiansException(scheme.k(), fetched.size());
    }
    return decoderPort.reconstruct(fetched, scheme);
  }

  /**
   * Validates that a {@code fileId} can be reconstructed structurally, manifest exists and the
   * placement set is well-formed (one row per {@code (blockIndex, fragmentIndex)} and count of
   * distinct fragmentIndex per block ≤ {@code n}). Does NOT fetch from peers; reusable as a cheap
   * preflight from the download path so that header-commit and body-streaming only happen when the
   * local catalog is consistent.
   *
   * @param fileId file identifier
   * @throws NoSuchElementException if the manifest does not exist
   * @throws InconsistentFragmentPlacementException if duplicates or excess fragments are detected
   */
  public void validateReconstructable(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      throw new IllegalArgumentException("fileId must not be blank");
    }
    final FileManifest manifest =
        fileManifestPort
            .findByFileId(fileId.trim())
            .orElseThrow(() -> new NoSuchElementException("manifest not found: " + fileId));
    final List<FragmentPlacement> placements = fragmentPlacementPort.findByFileId(fileId.trim());
    validatePlacementSet(fileId.trim(), placements);
    // Defense in depth: total placement count must not exceed n × #blocks. Manifest's
    // fragmentCount already encodes this product; using it directly avoids depending on
    // synthethicSingleBlock.
    if (placements.size() > manifest.fragmentCount()) {
      throw new InconsistentFragmentPlacementException(fileId.trim(), -1, -1, placements.size());
    }
  }

  /**
   * Probes whether at least {@code k} of the placements for {@code fileId} are reachable by issuing
   * a real fetch against each custodian of the first block. Used by the download preflight so the
   * controller can emit a clean 503 + JSON before committing octet-stream headers when peers are
   * down. Stops early once {@code k} succeed.
   *
   * @param fileId file identifier
   * @return {@code true} if at least {@code k} custodians of the first block responded
   */
  public boolean canReachEnoughCustodians(final String fileId) {
    if (fileId == null || fileId.isBlank()) {
      return false;
    }
    final FileManifest manifest = fileManifestPort.findByFileId(fileId.trim()).orElse(null);
    if (manifest == null) {
      return false;
    }
    final int k = manifest.redundancyK();
    final List<FragmentPlacement> placements = fragmentPlacementPort.findByFileId(fileId.trim());
    int firstBlockIndex = Integer.MAX_VALUE;
    for (FragmentPlacement p : placements) {
      if (p.blockIndex() < firstBlockIndex) {
        firstBlockIndex = p.blockIndex();
      }
    }
    int reached = 0;
    for (FragmentPlacement p : placements) {
      if (p.blockIndex() != firstBlockIndex) {
        continue;
      }
      try {
        remoteFragmentClient.fetchFragment(p.custodianBaseUrl(), p.fragmentId());
        reached++;
        if (reached >= k) {
          return true;
        }
      } catch (RuntimeException ignored) {
        // peer unreachable — try next
      }
    }
    return reached >= k;
  }

  /**
   * Groups placements by {@code (blockIndex, fragmentIndex)} and asserts each group has exactly one
   * row. The first duplicate found triggers {@link InconsistentFragmentPlacementException}. Used
   * both at preflight time and inside {@link #reconstructDownloadStreaming} for fail-fast
   * guarantee.
   */
  private void validatePlacementSet(final String fileId, final List<FragmentPlacement> placements) {
    final java.util.Map<Long, Integer> countsByCell = new java.util.HashMap<>();
    for (FragmentPlacement p : placements) {
      // Pack (blockIndex, fragmentIndex) into a long key, both are non-negative ints.
      final long key = ((long) p.blockIndex() << 32) | (p.fragmentIndex() & 0xFFFFFFFFL);
      final int newCount = countsByCell.merge(key, 1, Integer::sum);
      if (newCount > 1) {
        throw new InconsistentFragmentPlacementException(
            fileId, p.blockIndex(), p.fragmentIndex(), newCount);
      }
    }
  }

  /**
   * Resolves the {@code n} custodian base URLs that will receive every block's fragments for this
   * upload. Two paths:
   *
   * <ol>
   *   <li>Discovery wired ({@code discoveryWiring != null} and {@code
   *       discoveryWiring.remoteQueryClient() != null} con {@code discoverySupernodes} no vacío):
   *       el origen randomiza la cola de supernodos (failover round-robin), consulta signed HTTP
   *       {@code POST /ops/discovery/query} contra el primer supernodo respondiendo via {@link
   *       RemoteDiscoveryQueryClientPort#discover}; si lanza {@link DiscoveryUnreachableException}
   *       se intenta el siguiente supernodo. La lista resultante se dedupea por {@code baseUrl}, se
   *       reintenta con {@code requestSize *= 2} hasta {@code maxDiscoveryRetries} veces, y se
   *       falla con {@link InsufficientCustodiansException} si sigue por debajo de {@code n}.
   *       Cacheado via {@link DiscoveryQueryCache} con clave {@code (requestSize, plan)}, sin
   *       {@code supernodeBaseUrl} porque el modelo es replicado.
   *   <li>No Discovery (legacy/tests): require {@code custodianBaseUrls.size() >= n} and return the
   *       first {@code n} entries verbatim.
   * </ol>
   */
  private List<String> resolveCustodianBaseUrls(final int required) {
    if (discoveryWiring == null
        || discoveryWiring.remoteQueryClient() == null
        || discoveryWiring.discoverySupernodes() == null
        || discoveryWiring.discoverySupernodes().isEmpty()) {
      if (custodianBaseUrls.size() < required) {
        throw new InsufficientCustodiansException(required, custodianBaseUrls.size());
      }
      return custodianBaseUrls.subList(0, required);
    }

    // El origen consulta supernodos remotos vía signed HTTP. Una vez
    // por upload se randomiza la lista de supernodos para balancear carga; los retries con
    // bucket-size escalado se ejecutan sobre la misma cola shuffled (la cache key sigue
    // estable). Si un supernodo falla con DiscoveryUnreachableException, WARN + siguiente.
    final List<String> shuffledSupernodes = new ArrayList<>(discoveryWiring.discoverySupernodes());
    Collections.shuffle(shuffledSupernodes);

    final Map<String, Integer> plan =
        parseDistributionPlan(discoveryWiring.defaultDistributionPlan());
    int requestSize = (int) Math.max(required, Math.ceil(required * 1.5));
    final Set<String> deduped = new LinkedHashSet<>();
    boolean anySupernodeReachable = false;

    for (int attempt = 0; attempt < Math.max(1, discoveryWiring.maxDiscoveryRetries()); attempt++) {
      final int snapshotRequestSize = requestSize;
      final DiscoveryRequest request =
          new DiscoveryRequest(
              discoveryWiring.localNodeId(),
              discoveryWiring.localFailureDomain(),
              discoveryWiring.defaultRequestedBucket(),
              discoveryWiring.defaultRatio(),
              snapshotRequestSize,
              null,
              plan);
      final List<DiscoveryResponse.CandidateNode> candidates =
          queryAnySupernode(shuffledSupernodes, request, plan, snapshotRequestSize);
      if (candidates == null) {
        // Todos los supernodos no respondieron en este intento. NO escalamos requestSize en
        // ese caso (no aporta nada, el problema es de red, no de directorio insuficiente).
        continue;
      }
      anySupernodeReachable = true;

      for (DiscoveryResponse.CandidateNode c : candidates) {
        if (!discoveryWiring.allowSelfCandidate()
            && c.nodeId().equals(discoveryWiring.localNodeId())) {
          continue;
        }
        deduped.add(c.baseUrl());
      }
      if (deduped.size() >= required) {
        break;
      }
      requestSize = (int) Math.ceil(requestSize * 2.0);
    }

    if (!anySupernodeReachable) {
      LOGGER
          .atWarn()
          .setMessage("All discovery supernodes unreachable; upload aborted")
          .addKeyValue("event", "DISCOVERY_UNREACHABLE")
          .addKeyValue("supernodes", shuffledSupernodes)
          .addKeyValue("severity", "high")
          .log();
      throw new DiscoveryUnreachableException(
          null, "All configured discovery supernodes are unreachable: " + shuffledSupernodes);
    }

    if (deduped.size() < required) {
      LOGGER
          .atWarn()
          .setMessage("Discovery insufficient custodians after retries")
          .addKeyValue("event", "DISCOVERY_INSUFFICIENT_CUSTODIANS_AFTER_RETRY")
          .addKeyValue("required", required)
          .addKeyValue("found", deduped.size())
          .addKeyValue("retries", discoveryWiring.maxDiscoveryRetries())
          .addKeyValue("severity", "high")
          .log();
      throw new InsufficientCustodiansException(required, deduped.size());
    }

    return new ArrayList<>(deduped).subList(0, required);
  }

  /**
   * Itera la lista shuffled de supernodos hasta que uno responde. Devuelve {@code null} si TODOS
   * fallan con {@link DiscoveryUnreachableException} (caller decide qué hacer). El cache TTL vive
   * sobre la respuesta agregada (clave: requestSize + plan, sin supernodeBaseUrl porque en modelo
   * replicado todos los supernodos devuelven la misma cosa).
   */
  private List<DiscoveryResponse.CandidateNode> queryAnySupernode(
      final List<String> shuffledSupernodes,
      final DiscoveryRequest request,
      final Map<String, Integer> plan,
      final int snapshotRequestSize) {
    for (String supernodeBaseUrl : shuffledSupernodes) {
      try {
        if (discoveryWiring.discoveryQueryCache() == null) {
          return discoveryWiring
              .remoteQueryClient()
              .discover(supernodeBaseUrl, request)
              .candidates();
        }
        return discoveryWiring
            .discoveryQueryCache()
            .getOrCompute(
                cacheKey(plan, snapshotRequestSize),
                () ->
                    discoveryWiring
                        .remoteQueryClient()
                        .discover(supernodeBaseUrl, request)
                        .candidates());
      } catch (DiscoveryUnreachableException ex) {
        LOGGER
            .atWarn()
            .setMessage("Discovery supernode unreachable; failing over to next")
            .addKeyValue("event", "DISCOVERY_SUPERNODE_UNREACHABLE")
            .addKeyValue("supernodeBaseUrl", supernodeBaseUrl)
            .addKeyValue("cause", ex.getMessage())
            .log();
      }
    }
    return null;
  }

  private static Map<String, Integer> parseDistributionPlan(final String distributionPlan) {
    if (distributionPlan == null || distributionPlan.isBlank()) {
      return Map.of();
    }
    final Map<String, Integer> parsed = new LinkedHashMap<>();
    for (String entry : distributionPlan.split(",")) {
      final String trimmed = entry.trim();
      if (trimmed.isBlank()) {
        continue;
      }
      final String[] pair = trimmed.split(":");
      if (pair.length != 2) {
        throw new IllegalArgumentException("Invalid distribution plan entry: " + trimmed);
      }
      final int count;
      try {
        count = Integer.parseInt(pair[1].trim());
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException("Invalid distribution plan count for entry: " + trimmed);
      }
      if (count <= 0) {
        throw new IllegalArgumentException("Distribution plan counts must be greater than zero");
      }
      parsed.put(pair[0].trim(), count);
    }
    return Map.copyOf(parsed);
  }

  private static String cacheKey(final Map<String, Integer> plan, final int requestSize) {
    final StringBuilder builder = new StringBuilder(64);
    builder.append("rs:").append(requestSize).append(":");
    for (Map.Entry<String, Integer> e : plan.entrySet()) {
      builder.append(e.getKey()).append('=').append(e.getValue()).append(';');
    }
    return builder.toString();
  }

  /**
   * Synthesizes a single-block view from a legacy manifest so that {@link #reconstructDownload} can
   * iterate blocks uniformly.
   */
  private static BlockManifest syntheticSingleBlock(final FileManifest manifest) {
    return new BlockManifest(
        0, manifest.originalSizeBytes(), manifest.originalFileHash(), manifest.fragmentHashes());
  }

  /**
   * Releases reserved quota for a file (called by {@code FileSystemService.delete()} so the
   * deletion frees the user's quota).
   *
   * @param username owner username
   * @param fileId file identifier
   */
  public void releaseQuotaForFile(final String username, final String fileId) {
    if (username == null || username.isBlank() || fileId == null || fileId.isBlank()) {
      return;
    }
    final Optional<FileManifest> manifest = fileManifestPort.findByFileId(fileId.trim());
    if (manifest.isEmpty()) {
      return;
    }
    final FileManifest m = manifest.get();
    final long chargedBytes = (m.originalSizeBytes() * m.redundancyN()) / m.redundancyK();
    userQuotaPort.release(username, chargedBytes);
    fileManifestPort.deleteByFileId(fileId.trim());
    fragmentPlacementPort.deleteByFileId(fileId.trim());
  }

  private long computeAvailable(final String username, final long requestedBytes) {
    return Math.max(0L, requestedBytes - 1L) - userQuotaPort.usedBytes(username);
  }

  private String deriveCustodianNodeId(final String baseUrl) {
    return "peer@" + baseUrl;
  }

  private String extractFileName(final String path, final String fallback) {
    final int slash = path.lastIndexOf('/');
    if (slash < 0 || slash == path.length() - 1) {
      return fallback;
    }
    return path.substring(slash + 1);
  }

  /**
   * Extrae la carpeta padre de un path absoluto. {@code /foo/bar.txt} → {@code /foo}; {@code
   * /bar.txt} → {@code /}; {@code null} o blank → {@code /}. Coherente con la regex
   * `DIRECTORY_PATH` del FileManifest, que valida segmentos de carpeta, pasarle el path completo
   * (incluido el filename con espacios u otros caracteres) la rompe.
   */
  private String extractParentPath(final String path) {
    if (path == null || path.isBlank()) {
      return "/";
    }
    final int slash = path.lastIndexOf('/');
    if (slash <= 0) {
      return "/";
    }
    return path.substring(0, slash);
  }

  private static byte[] readExactly(final InputStream input, final int target) {
    final byte[] buffer = new byte[target];
    int offset = 0;
    while (offset < target) {
      final int read;
      try {
        read = input.read(buffer, offset, target - offset);
      } catch (IOException ex) {
        throw new IllegalStateException("I/O error reading upload stream", ex);
      }
      if (read < 0) {
        // EOF earlier than declared, return what we have so the caller can detect mismatch.
        return java.util.Arrays.copyOf(buffer, offset);
      }
      offset += read;
    }
    return buffer;
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 not available", exception);
    }
  }

  private static String sha256Hex(final byte[] payload) {
    final MessageDigest digest = newSha256();
    return HexFormat.of().formatHex(digest.digest(payload)).toLowerCase(java.util.Locale.ROOT);
  }

  /** Used internally by tests via reflection only; kept to silence unused import warnings. */
  @SuppressWarnings("unused")
  private static <T> List<T> immutable(final List<T> in) {
    return in == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(in));
  }
}
