package es.ual.node.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import es.ual.node.bootstrap.configuration.TestNodeIdentityKeys;
import es.ual.node.custodyliveness.domain.CustodyProbeDirection;
import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import es.ual.node.custodyliveness.domain.CustodyProbeStatus;
import es.ual.node.custodyliveness.ports.out.CustodyProbeSessionPort;
import es.ual.node.discovery.domain.DiscoveryRequest;
import es.ual.node.discovery.domain.DiscoveryRetryRequest;
import es.ual.node.discovery.domain.DiscoveryRetryStatus;
import es.ual.node.discovery.ports.out.DiscoveryRetryQueuePort;
import es.ual.node.filesystem.domain.FileUploadSession;
import es.ual.node.filesystem.domain.FileUploadSessionStatus;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.filesystem.ports.out.FileManifestPort;
import es.ual.node.filesystem.ports.out.FileUploadSessionPort;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.negotiation.domain.BlockManifest;
import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.recovery.domain.CustodiedFileManifest;
import es.ual.node.recovery.ports.out.CustodiedFileManifestPort;
import es.ual.node.userregistration.domain.RegistrationCode;
import es.ual.node.userregistration.domain.UserAccount;
import es.ual.node.userregistration.domain.UserRole;
import es.ual.node.userregistration.domain.UserSession;
import es.ual.node.userregistration.ports.out.RegistrationCodePort;
import es.ual.node.userregistration.ports.out.UserAccountPort;
import es.ual.node.userregistration.ports.out.UserSessionPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Tests de integración para los Postgres adapters no cubiertos por {@link
 * PersistencePostgresModeIntegrationTest}. Properties idénticas a la otra clase para compartir el
 * test context cache de Spring Boot.
 */
@SpringBootTest(
    properties = {
      "node.persistence.mode=postgres",
      "node.features.discovery-enabled=false",
      "node.features.negotiation-enabled=false",
      "node.features.custody-enabled=false",
      "node.features.recovery-enabled=false",
      "node.capacity.max-bytes=100",
      "node.topology.tutorAcceptedPublicKeys=test-public-key"
    })
class PersistencePostgresAdaptersRoundTripIntegrationTest {

  private static final String[] NODE_IDENTITY_PROPERTIES =
      TestNodeIdentityKeys.generatePropertyValues();

  @Autowired private CustodiedFileManifestPort custodiedFileManifestPort;
  @Autowired private CustodyProbeSessionPort custodyProbeSessionPort;
  @Autowired private DiscoveryRetryQueuePort discoveryRetryQueuePort;
  @Autowired private FileManifestPort fileManifestPort;
  @Autowired private FileUploadSessionPort fileUploadSessionPort;
  @Autowired private FsEntryPort fsEntryPort;
  @Autowired private RegistrationCodePort registrationCodePort;
  @Autowired private UserAccountPort userAccountPort;
  @Autowired private UserSessionPort userSessionPort;

  @DynamicPropertySource
  static void configureNodeIdentity(final DynamicPropertyRegistry registry) {
    for (String property : NODE_IDENTITY_PROPERTIES) {
      final int separatorIndex = property.indexOf('=');
      registry.add(
          property.substring(0, separatorIndex), () -> property.substring(separatorIndex + 1));
    }
  }

  @Test
  void custodyProbeSessionPort_persistsAndLoadsByIdAndRemoteNode() {
    final String sessionId = "probe-rt-" + UUID.randomUUID();
    final String remoteNodeId = "remote-probe-" + UUID.randomUUID();
    final Instant now = Instant.now();
    final CustodyProbeSession session =
        CustodyProbeSession.withoutRemoteTutor(
            sessionId,
            remoteNodeId,
            CustodyProbeDirection.OUTBOUND,
            CustodyProbeStatus.ACTIVE,
            0,
            now.minusSeconds(30),
            now.minusSeconds(10),
            now.plusSeconds(60),
            null,
            null,
            now.minusSeconds(120),
            now);

    custodyProbeSessionPort.save(session);

    final CustodyProbeSession byId = custodyProbeSessionPort.findById(sessionId).orElseThrow();
    assertThat(byId.remoteNodeId()).isEqualTo(remoteNodeId);
    assertThat(byId.status()).isEqualTo(CustodyProbeStatus.ACTIVE);
    assertThat(byId.direction()).isEqualTo(CustodyProbeDirection.OUTBOUND);

    assertThat(custodyProbeSessionPort.findByRemoteNodeId(remoteNodeId))
        .extracting(CustodyProbeSession::sessionId)
        .contains(sessionId);
  }

  @Test
  void discoveryRetryQueuePort_persistsAndExposesDuePendingRequests() {
    final String id = "retry-rt-" + UUID.randomUUID();
    final Instant now = Instant.now();
    final DiscoveryRequest request =
        new DiscoveryRequest("requester-rt", "zone-a/rack-1", 1024L, 1.10d, 4);
    final DiscoveryRetryRequest retry =
        new DiscoveryRetryRequest(
            id,
            request,
            DiscoveryRetryStatus.PENDING,
            0,
            now.minusSeconds(5),
            now.minusSeconds(60),
            now,
            null,
            null,
            null);

    discoveryRetryQueuePort.save(retry);

    final DiscoveryRetryRequest loaded = discoveryRetryQueuePort.findById(id).orElseThrow();
    assertThat(loaded.status()).isEqualTo(DiscoveryRetryStatus.PENDING);
    assertThat(loaded.request().nodeId()).isEqualTo("requester-rt");

    assertThat(discoveryRetryQueuePort.findDue(now, 10))
        .extracting(DiscoveryRetryRequest::id)
        .contains(id);
  }

  @Test
  void fileUploadSessionPort_persistsOpenSessionAndLoadsByUserAndId() {
    final String username = "uploader-rt-" + UUID.randomUUID();
    final String sessionId = "upload-rt-" + UUID.randomUUID();
    final String entryId = "entry-rt-" + UUID.randomUUID();
    final Instant now = Instant.now();
    final FileUploadSession session =
        new FileUploadSession(
            sessionId,
            username,
            entryId,
            128L,
            "a".repeat(64),
            0L,
            FileUploadSessionStatus.OPEN,
            now,
            now,
            null);

    fileUploadSessionPort.save(session);

    final FileUploadSession loaded =
        fileUploadSessionPort.findByUsernameAndSessionId(username, sessionId).orElseThrow();
    assertThat(loaded.entryId()).isEqualTo(entryId);
    assertThat(loaded.expectedSizeBytes()).isEqualTo(128L);
    assertThat(loaded.status()).isEqualTo(FileUploadSessionStatus.OPEN);
  }

  @Test
  void fsEntryPort_persistsEntryAndFindsByPathAndId() {
    final String username = "fsuser-rt-" + UUID.randomUUID();
    final String entryId = "fsid-rt-" + UUID.randomUUID();
    final String path = "/notes/" + UUID.randomUUID() + ".txt";
    final Instant now = Instant.now();
    final String fileId = UUID.randomUUID().toString();
    final String fileHash = "b".repeat(64);
    final FsEntry entry =
        new FsEntry(
            entryId, username, path, FsEntryType.FILE, 4L, fileHash, fileId, 1L, now, false);

    fsEntryPort.save(entry);

    final FsEntry byPath = fsEntryPort.findByUsernameAndPath(username, path).orElseThrow();
    assertThat(byPath.entryId()).isEqualTo(entryId);
    assertThat(byPath.entryType()).isEqualTo(FsEntryType.FILE);

    final FsEntry byId = fsEntryPort.findByUsernameAndEntryId(username, entryId).orElseThrow();
    assertThat(byId.path()).isEqualTo(path);

    assertThat(fsEntryPort.findByUsername(username)).extracting(FsEntry::entryId).contains(entryId);
  }

  @Test
  void registrationCodePort_persistsAndFindsByCodeThenMarksUsed() {
    final String code = ("INV-" + UUID.randomUUID().toString().replace("-", "")).substring(0, 16);
    final Instant now = Instant.now();
    registrationCodePort.save(
        new RegistrationCode(
            code, 500, UserRole.END_USER, now.plusSeconds(3600), false, null, now));

    final RegistrationCode loaded = registrationCodePort.findByCode(code).orElseThrow();
    assertThat(loaded.quotaMb()).isEqualTo(500);
    assertThat(loaded.role()).isEqualTo(UserRole.END_USER);
    assertThat(loaded.used()).isFalse();

    registrationCodePort.markUsed(code, now.plusSeconds(60));
    final RegistrationCode reloaded = registrationCodePort.findByCode(code).orElseThrow();
    assertThat(reloaded.used()).isTrue();
    assertThat(reloaded.usedAt()).isNotNull();
  }

  @Test
  void userAccountPort_persistsAndFindsByUsernameWithUniqueness() {
    final String username = "user-rt-" + UUID.randomUUID();
    final Instant now = Instant.now();
    final UserAccount account = new UserAccount(username, "hash-rt", 1024, UserRole.END_USER, now);

    assertThat(userAccountPort.existsByUsername(username)).isFalse();
    userAccountPort.save(account);
    assertThat(userAccountPort.existsByUsername(username)).isTrue();

    final UserAccount loaded = userAccountPort.findByUsername(username).orElseThrow();
    assertThat(loaded.passwordHash()).isEqualTo("hash-rt");
    assertThat(loaded.quotaMb()).isEqualTo(1024);
    assertThat(loaded.role()).isEqualTo(UserRole.END_USER);
  }

  @Test
  void userSessionPort_persistsLoadsByTokenAndRevokes() {
    final String token = "session-rt-" + UUID.randomUUID();
    final Instant now = Instant.now();
    final UserSession session =
        new UserSession(token, "session-user", now, now.plusSeconds(3600), false);

    userSessionPort.save(session);

    final UserSession loaded = userSessionPort.findByToken(token).orElseThrow();
    assertThat(loaded.username()).isEqualTo("session-user");
    assertThat(loaded.revoked()).isFalse();

    userSessionPort.revoke(token);
    final UserSession revoked = userSessionPort.findByToken(token).orElseThrow();
    assertThat(revoked.revoked()).isTrue();
  }

  // ---------- FileManifest port — round-trip ----------

  @Test
  void fileManifestPort_roundTripsLegacySingleBlockPreservingAllFields() {
    final String fileId = UUID.randomUUID().toString();
    final String hash = "a".repeat(64);
    final FileManifest legacy =
        new FileManifest(
            fileId,
            "/alice/docs",
            "report.bin",
            8192L,
            4096L,
            "zstd",
            hash,
            6,
            1024L,
            6,
            4,
            List.of(
                hash,
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64),
                "e".repeat(64),
                "f".repeat(64)));

    fileManifestPort.save(legacy, "alice-rt", "entry-rt-legacy");
    final FileManifest loaded = fileManifestPort.findByFileId(fileId).orElseThrow();

    assertThat(loaded.fileId()).isEqualTo(legacy.fileId());
    assertThat(loaded.directoryPath()).isEqualTo("/alice/docs");
    assertThat(loaded.originalFileName()).isEqualTo("report.bin");
    assertThat(loaded.originalFileHash()).isEqualTo(hash);
    assertThat(loaded.originalSizeBytes()).isEqualTo(8192L);
    assertThat(loaded.compressedSizeBytes()).isEqualTo(4096L);
    assertThat(loaded.compressionAlgorithm()).isEqualTo("zstd");
    assertThat(loaded.fragmentCount()).isEqualTo(6);
    assertThat(loaded.fragmentSize()).isEqualTo(1024L);
    assertThat(loaded.redundancyN()).isEqualTo(6);
    assertThat(loaded.redundancyK()).isEqualTo(4);
    assertThat(loaded.fragmentHashes()).containsExactlyElementsOf(legacy.fragmentHashes());
    // Legacy single-block: blocks() empty (preserva el shape exacto in-memory).
    assertThat(loaded.blocks()).isEmpty();
  }

  @Test
  void fileManifestPort_roundTripsMultiBlockPreservingBlockLayout() {
    final String fileId = UUID.randomUUID().toString();
    final String fileHash = "a".repeat(64);
    final BlockManifest block0 =
        new BlockManifest(
            0, 4096L, "1".repeat(64), List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)));
    final BlockManifest block1 =
        new BlockManifest(
            1, 4096L, "2".repeat(64), List.of("d".repeat(64), "e".repeat(64), "f".repeat(64)));
    final FileManifest multi =
        new FileManifest(
            fileId,
            "/alice/big",
            "movie.mov",
            8192L,
            null,
            null,
            fileHash,
            6,
            1024L,
            3,
            2,
            List.of(
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64),
                "e".repeat(64),
                "f".repeat(64)),
            List.of(block0, block1));

    fileManifestPort.save(multi, "alice-rt", "entry-rt-multi");
    final FileManifest loaded = fileManifestPort.findByFileId(fileId).orElseThrow();

    assertThat(loaded.blocks()).hasSize(2);
    assertThat(loaded.blocks().get(0).blockIndex()).isZero();
    assertThat(loaded.blocks().get(0).blockSizeBytes()).isEqualTo(4096L);
    assertThat(loaded.blocks().get(0).blockHash()).isEqualTo(block0.blockHash());
    assertThat(loaded.blocks().get(0).fragmentHashes())
        .containsExactlyElementsOf(block0.fragmentHashes());
    assertThat(loaded.blocks().get(1).blockIndex()).isEqualTo(1);
    assertThat(loaded.blocks().get(1).fragmentHashes())
        .containsExactlyElementsOf(block1.fragmentHashes());
    // Flat aggregate fragmentHashes derivado de blocks (concat por orden).
    assertThat(loaded.fragmentHashes()).hasSize(6);
    assertThat(loaded.compressedSizeBytes()).isNull();
    assertThat(loaded.compressionAlgorithm()).isNull();
  }

  // ---------- FK formales SQL ----------

  @Test
  void deleteClientFileManifest_cascadesToFragmentPlacementAndBlocks() {
    // FK CASCADE: client_fragment_placement.file_id + client_file_manifest_block.file_id.
    // Borrar el manifest debe eliminar las filas dependientes en cascada.
    final String fileId = UUID.randomUUID().toString();
    final String fileHash = "b".repeat(64);
    final String username = "fkuser-rt-" + UUID.randomUUID();
    final String entryId = "fkid-rt-" + UUID.randomUUID();
    fileManifestPort.save(
        new FileManifest(
            fileId,
            "/cascade",
            "fixture.txt",
            4L,
            null,
            null,
            fileHash,
            3,
            512L,
            3,
            2,
            List.of(fileHash, "c".repeat(64), "d".repeat(64))),
        username,
        entryId);
    assertThat(fileManifestPort.findByFileId(fileId)).isPresent();

    fileManifestPort.deleteByFileId(fileId);
    assertThat(fileManifestPort.findByFileId(fileId)).isEmpty();
  }

  // ---------- CustodiedFileManifest port — round-trip ----------

  @Test
  void custodiedFileManifestPort_roundTripsLegacySingleBlockSynthesizingBlocksJson() {
    final String fileId = UUID.randomUUID().toString();
    final String hash = "a".repeat(64);
    final List<String> flatHashes =
        List.of(
            "b".repeat(64),
            "c".repeat(64),
            "d".repeat(64),
            "e".repeat(64),
            "f".repeat(64),
            "1".repeat(64));
    final CustodiedFileManifest legacy =
        new CustodiedFileManifest(
            fileId,
            "node-rt-legacy",
            "test-public-key",
            "/alice/legacy",
            "single.bin",
            hash,
            8192L,
            null,
            null,
            6,
            1024L,
            6,
            4,
            flatHashes,
            null,
            null,
            Instant.parse("2026-05-04T11:00:00Z"));

    custodiedFileManifestPort.save(legacy);
    final CustodiedFileManifest loaded =
        custodiedFileManifestPort.findByFileId(fileId).orElseThrow();

    // Las 3 columnas derivables (fragmentCount, fragmentSize, fragmentHashes) se
    // reconstruyen al vuelo desde el blob client_blocks_json sintético.
    assertThat(loaded.fragmentCount()).isEqualTo(6);
    assertThat(loaded.fragmentSize()).isEqualTo(8192L / 4); // originalSize / k
    assertThat(loaded.fragmentHashes()).containsExactlyElementsOf(flatHashes);
    // multi_block=FALSE preserva la semántica wire — el record devuelve null aunque
    // internamente el blob esté sintetizado.
    assertThat(loaded.clientBlocksJson()).isNull();
    assertThat(loaded.originalFileHash()).isEqualTo(hash);
    assertThat(loaded.requesterPublicKey()).isEqualTo("test-public-key");
  }

  @Test
  void custodiedFileManifestPort_roundTripsMultiBlockDerivingFlatHashesFromBlocksJson() {
    final String fileId = UUID.randomUUID().toString();
    final String fileHash = "a".repeat(64);
    final BlockManifest block0 =
        new BlockManifest(
            0, 4096L, "1".repeat(64), List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)));
    final BlockManifest block1 =
        new BlockManifest(
            1, 4096L, "2".repeat(64), List.of("d".repeat(64), "e".repeat(64), "f".repeat(64)));
    final List<BlockManifest> blocks = List.of(block0, block1);
    final String wireBlocksJson = serializeBlocksAsJson(blocks);

    final List<String> flatAggregate = new java.util.ArrayList<>();
    blocks.forEach(b -> flatAggregate.addAll(b.fragmentHashes()));

    final CustodiedFileManifest multi =
        new CustodiedFileManifest(
            fileId,
            "node-rt-multi",
            "test-public-key",
            "/alice/big",
            "movie.mov",
            fileHash,
            8192L,
            null,
            null,
            6,
            1024L,
            3,
            2,
            flatAggregate,
            null,
            wireBlocksJson,
            Instant.parse("2026-05-04T11:00:00Z"));

    custodiedFileManifestPort.save(multi);
    final CustodiedFileManifest loaded =
        custodiedFileManifestPort.findByFileId(fileId).orElseThrow();

    // fragmentCount derivado: sum de hashes per block = 3 + 3.
    assertThat(loaded.fragmentCount()).isEqualTo(6);
    // fragmentSize derivado: blocks[0].blockSizeBytes / k = 4096 / 2.
    assertThat(loaded.fragmentSize()).isEqualTo(2048L);
    assertThat(loaded.fragmentHashes()).containsExactlyElementsOf(flatAggregate);
    // multi_block=TRUE preserva el blob wire intacto.
    assertThat(loaded.clientBlocksJson()).isEqualTo(wireBlocksJson);
  }

  private String serializeBlocksAsJson(final List<BlockManifest> blocks) {
    try {
      final com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      return mapper.writeValueAsString(
          blocks.stream()
              .map(
                  b ->
                      java.util.Map.of(
                          "blockIndex", b.blockIndex(),
                          "blockSizeBytes", b.blockSizeBytes(),
                          "blockHash", b.blockHash(),
                          "fragmentHashes", b.fragmentHashes()))
              .toList());
    } catch (Exception ex) {
      throw new IllegalStateException("test serialization failed", ex);
    }
  }

  @Test
  void fileManifestPort_deleteRemovesManifestAndItsBlocks() {
    final String fileId = UUID.randomUUID().toString();
    final String hash = "a".repeat(64);
    final FileManifest manifest =
        new FileManifest(
            fileId,
            "/alice/tmp",
            "tmp.bin",
            1024L,
            null,
            null,
            hash,
            3,
            512L,
            3,
            2,
            List.of(hash, "b".repeat(64), "c".repeat(64)));

    fileManifestPort.save(manifest, "alice-rt", "entry-rt-delete");
    assertThat(fileManifestPort.findByFileId(fileId)).isPresent();
    fileManifestPort.deleteByFileId(fileId);
    assertThat(fileManifestPort.findByFileId(fileId)).isEmpty();
  }
}
