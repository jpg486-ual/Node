package es.ual.node.filesystem.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.filesystem.adapters.out.memory.InMemoryFileManifestPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFragmentPlacementPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFsEntryPort;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.filesystem.ports.out.RemoteFragmentDistributionClientPort;
import es.ual.node.recovery.adapters.out.memory.InMemoryCustodiedFileManifestPort;
import es.ual.node.recovery.domain.CustodiedFileManifest;
import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsCodecAdapter;
import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsIntegrityVerifier;
import es.ual.node.reedsolomon.domain.RsScheme;
import es.ual.node.userregistration.adapters.out.memory.InMemoryUserAccountPort;
import es.ual.node.userregistration.adapters.out.memory.InMemoryUserQuotaPort;
import es.ual.node.userregistration.domain.UserAccount;
import es.ual.node.userregistration.domain.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for filesystem metadata service. */
class FileSystemServiceTest {

  @Test
  void upsertIncrementsVersionForSamePath() {
    final Clock clock = Clock.fixed(Instant.parse("2026-03-07T12:00:00Z"), ZoneOffset.UTC);
    final FileSystemService service = new FileSystemService(new InMemoryFsEntryPort(), clock);

    final FsEntry first =
        service.upsert(
            new FsUpsertRequest(
                "alice",
                null,
                "/docs/readme.txt",
                FsEntryType.FILE,
                100L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                UUID.randomUUID().toString(),
                false));

    final FsEntry second =
        service.upsert(
            new FsUpsertRequest(
                "alice",
                first.entryId(),
                "/docs/readme.txt",
                FsEntryType.FILE,
                120L,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                UUID.randomUUID().toString(),
                false));

    assertEquals(1L, first.version());
    assertEquals(2L, second.version());
    assertEquals(first.entryId(), second.entryId());
  }

  @Test
  void treeWithCursorReturnsOnlyIncrementalChanges() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock baseClock = Clock.fixed(Instant.parse("2026-03-07T12:00:00Z"), ZoneOffset.UTC);
    final FileSystemService serviceAtT0 = new FileSystemService(port, baseClock);

    final var aId =
        serviceAtT0
            .upsert(
                new FsUpsertRequest(
                    "alice",
                    null,
                    "/docs/a.txt",
                    FsEntryType.FILE,
                    1L,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    UUID.randomUUID().toString(),
                    false))
            .entryId();
    // Simular fin del pipeline de distribución para que el entry pase a contentUploaded=true.
    port.findByUsernameAndEntryId("alice", aId)
        .map(e -> e.withContentUploaded(true))
        .ifPresent(port::save);

    final FsTreeSnapshot firstSnapshot = serviceAtT0.tree("alice", null);
    assertEquals(1, firstSnapshot.entries().size());
    assertTrue(firstSnapshot.cursor() > 0L);

    final Clock t1Clock = Clock.fixed(Instant.parse("2026-03-07T12:10:00Z"), ZoneOffset.UTC);
    final FileSystemService serviceAtT1 = new FileSystemService(port, t1Clock);
    final var bId =
        serviceAtT1
            .upsert(
                new FsUpsertRequest(
                    "alice",
                    null,
                    "/docs/b.txt",
                    FsEntryType.FILE,
                    2L,
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    UUID.randomUUID().toString(),
                    false))
            .entryId();
    port.findByUsernameAndEntryId("alice", bId)
        .map(e -> e.withContentUploaded(true))
        .ifPresent(port::save);

    final FsTreeSnapshot incremental = serviceAtT1.tree("alice", firstSnapshot.cursor());
    assertEquals(1, incremental.entries().size());
    assertEquals("/docs/b.txt", incremental.entries().getFirst().path());
  }

  @Test
  void patchThrowsConflictWhenTargetPathAlreadyExists() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-03-07T12:00:00Z"), ZoneOffset.UTC);
    final FileSystemService service = new FileSystemService(port, clock);

    final FsEntry first =
        service.upsert(
            new FsUpsertRequest(
                "alice",
                null,
                "/docs/a.txt",
                FsEntryType.FILE,
                1L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                UUID.randomUUID().toString(),
                false));

    service.upsert(
        new FsUpsertRequest(
            "alice",
            null,
            "/docs/b.txt",
            FsEntryType.FILE,
            2L,
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            UUID.randomUUID().toString(),
            false));

    assertThrows(
        FsPathConflictException.class,
        () -> service.patch(new FsPatchRequest("alice", first.entryId(), "/docs/b.txt")));
  }

  @Test
  void patchMovesEntryAndDeleteMarksEntryAsDeleted() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock t0Clock = Clock.fixed(Instant.parse("2026-03-07T12:00:00Z"), ZoneOffset.UTC);
    final FileSystemService serviceAtT0 = new FileSystemService(port, t0Clock);

    final FsEntry created =
        serviceAtT0.upsert(
            new FsUpsertRequest(
                "alice",
                null,
                "/docs/c.txt",
                FsEntryType.FILE,
                3L,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                UUID.randomUUID().toString(),
                false));

    final Clock t1Clock = Clock.fixed(Instant.parse("2026-03-07T12:05:00Z"), ZoneOffset.UTC);
    final FileSystemService serviceAtT1 = new FileSystemService(port, t1Clock);
    final FsEntry moved =
        serviceAtT1.patch(new FsPatchRequest("alice", created.entryId(), "/archive/c.txt"));

    assertEquals("/archive/c.txt", moved.path());
    assertEquals(2L, moved.version());

    final Clock t2Clock = Clock.fixed(Instant.parse("2026-03-07T12:10:00Z"), ZoneOffset.UTC);
    final FileSystemService serviceAtT2 = new FileSystemService(port, t2Clock);
    final FsEntry deleted = serviceAtT2.delete("alice", created.entryId());

    assertTrue(deleted.deleted());
    assertEquals(3L, deleted.version());
  }

  @Test
  void deleteClearsHeavyMetadataAndPurgesRecoveryManifest() {
    // Tras DELETE el fs_entry queda como tombstone (sizeBytes=0, checksum=null,
    // fileId=null) y el recovery_file_manifest del tutor se purga.
    final InMemoryFsEntryPort entryPort = new InMemoryFsEntryPort();
    final InMemoryCustodiedFileManifestPort manifestPort = new InMemoryCustodiedFileManifestPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-03-07T12:00:00Z"), ZoneOffset.UTC);
    final FileSystemService service = new FileSystemService(entryPort, clock, manifestPort);

    final String fileId = UUID.randomUUID().toString();
    final FsEntry created =
        service.upsert(
            new FsUpsertRequest(
                "alice",
                null,
                "/docs/secret.bin",
                FsEntryType.FILE,
                42L,
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                fileId,
                false));

    // Sembramos un manifest custodiado para el fileId asociado (simula el
    // estado post-upload distribuido).
    manifestPort.save(
        new CustodiedFileManifest(
            fileId,
            "node-1",
            "pubkey-base64",
            "/docs",
            "secret.bin",
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            42L,
            null,
            null,
            4,
            1024L,
            6,
            4,
            List.of("h1", "h2", "h3", "h4"),
            null,
            null,
            Instant.parse("2026-03-07T12:00:00Z")));

    final FsEntry deleted = service.delete("alice", created.entryId());

    // Tombstone metadata clearing.
    assertTrue(deleted.deleted());
    assertEquals(0L, deleted.sizeBytes(), "sizeBytes debe quedar a 0 tras delete.");
    assertNull(deleted.checksum(), "checksum debe quedar nulo tras delete.");
    assertNull(deleted.fileId(), "fileId debe nullificarse para romper la referencia al manifest.");

    // Manifest hard-delete.
    assertFalse(
        manifestPort.findByFileId(fileId).isPresent(),
        "El recovery_file_manifest debe haberse purgado en el mismo flow.");
  }

  @Test
  void deleteManglesPathToTombstoneNamespace() {
    // El path tras delete queda bajo `/__deleted__/<entryId>/...` para que la
    // unique constraint `(username, path)` no bloquee futuros uploads al path original.
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-03-07T12:00:00Z"), ZoneOffset.UTC);
    final FileSystemService service = new FileSystemService(port, clock);

    final FsEntry created =
        service.upsert(
            new FsUpsertRequest(
                "alice",
                null,
                "/docs/secret.bin",
                FsEntryType.FILE,
                10L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                UUID.randomUUID().toString(),
                false));

    final FsEntry deleted = service.delete("alice", created.entryId());

    final String expected = "/__deleted__/" + created.entryId() + "/docs/secret.bin";
    assertEquals(
        expected,
        deleted.path(),
        "Tombstone debe vivir en /__deleted__/<entryId>/<originalPathStripped>.");
    assertTrue(deleted.deleted());
  }

  @Test
  void upsertRejectsTombstoneNamespacePrefix() {
    // Anti-spoofing: ningún input usuario puede crear / mover / mantener una
    // entrada en el namespace reservado `/__deleted__/...`.
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-03-07T12:00:00Z"), ZoneOffset.UTC);
    final FileSystemService service = new FileSystemService(port, clock);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.upsert(
                new FsUpsertRequest(
                    "alice",
                    null,
                    "/__deleted__/spoofed/file.txt",
                    FsEntryType.FILE,
                    1L,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    UUID.randomUUID().toString(),
                    false)));
  }

  @Test
  void upsertOnPathOfDeletedEntry_succeedsWithFreshEntry() {
    // Tras DELETE el path queda libre para un nuevo upload. Service-level enforcement:
    // si findByUsernameAndPath devuelve un tombstone, lo tratamos como path
    // libre y permitimos crear una entrada nueva con UUID fresh.
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-03-07T12:00:00Z"), ZoneOffset.UTC);
    final FileSystemService service =
        new FileSystemService(port, clock, new InMemoryCustodiedFileManifestPort());

    final FsEntry created =
        service.upsert(
            new FsUpsertRequest(
                "alice",
                null,
                "/notes.txt",
                FsEntryType.FILE,
                10L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                UUID.randomUUID().toString(),
                false));

    service.delete("alice", created.entryId());

    // Reupload con UUID fresh (sin requestEntryId): antes 409, ahora ok.
    final FsEntry recreated =
        service.upsert(
            new FsUpsertRequest(
                "alice",
                null,
                "/notes.txt",
                FsEntryType.FILE,
                20L,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                UUID.randomUUID().toString(),
                false));

    assertEquals("/notes.txt", recreated.path());
    assertFalse(recreated.deleted());
    assertEquals(20L, recreated.sizeBytes());
  }

  @Test
  void upsertThrowsConflictWhenPathExistsWithoutEntryId() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-03-07T12:00:00Z"), ZoneOffset.UTC);
    final FileSystemService service = new FileSystemService(port, clock);

    service.upsert(
        new FsUpsertRequest(
            "alice",
            null,
            "/docs/x.txt",
            FsEntryType.FILE,
            7L,
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
            UUID.randomUUID().toString(),
            false));

    assertThrows(
        FsPathConflictException.class,
        () ->
            service.upsert(
                new FsUpsertRequest(
                    "alice",
                    null,
                    "/docs/x.txt",
                    FsEntryType.FILE,
                    9L,
                    "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy",
                    UUID.randomUUID().toString(),
                    false)));
  }

  @Test
  void deleteReleasesUserQuotaWhenDistributionAvailable() {
    // El delete de un FsEntry distribuido libera la cuota y purga manifest + placements
    // vía FileContentDistributionService.releaseQuotaForFile. Sin orchestrator (modo
    // legacy blob local) el delete no toca cuotas.
    final InMemoryFsEntryPort entryPort = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-01T20:00:00Z"), ZoneOffset.UTC);

    final InMemoryFileManifestPort manifestPort = new InMemoryFileManifestPort();
    final InMemoryFragmentPlacementPort placementPort = new InMemoryFragmentPlacementPort();
    final RecordingRemoteClient remoteClient = new RecordingRemoteClient();
    final InMemoryUserAccountPort accountPort = new InMemoryUserAccountPort();
    accountPort.save(new UserAccount("alice", "hash", 100, UserRole.END_USER, Instant.EPOCH));
    final InMemoryUserQuotaPort quotaPort = new InMemoryUserQuotaPort(accountPort);
    final InMemoryRsCodecAdapter rsCodec = new InMemoryRsCodecAdapter();
    // Pasar entryPort al ctor para que el upload pipeline sincronice FsEntry.fileId con
    // el fileId generado. Imprescindible para que delete lea el fileId actual y purgue
    // las filas de manifest/placement correctas.
    final FileContentDistributionService orchestrator =
        new FileContentDistributionService(
            rsCodec,
            rsCodec,
            new InMemoryRsIntegrityVerifier(),
            manifestPort,
            placementPort,
            remoteClient,
            quotaPort,
            clock,
            new RsScheme(3, 2, 16),
            10L * 1024L * 1024L,
            Integer.MAX_VALUE,
            List.of("http://node1:8080", "http://node2:8080", "http://node3:8080"),
            null,
            null,
            entryPort);

    final FileSystemService service =
        new FileSystemService(
            entryPort, clock, new InMemoryCustodiedFileManifestPort(), orchestrator);

    // El orchestrator valida el SHA-256 de los bytes streameados contra entry.checksum();
    // el test entry debe declarar el hash real del payload ("demo").
    final byte[] payload = "demo".getBytes();
    final String payloadSha256 = sha256HexOf(payload);
    final FsEntry created =
        service.upsert(
            new FsUpsertRequest(
                "alice",
                null,
                "/big.bin",
                FsEntryType.FILE,
                (long) payload.length,
                payloadSha256,
                UUID.randomUUID().toString(),
                false));

    // Simulate the upload going through the orchestrator (charges quota +
    // persists manifest + placements). After this, the entryPort's FsEntry has been updated with
    // the freshly generated fileId; we re-read it before assertions on manifest/placements.
    orchestrator.distributeUpload("alice", created, payload);
    final String activeFileId =
        entryPort.findByUsernameAndEntryId("alice", created.entryId()).orElseThrow().fileId();
    assertTrue(quotaPort.usedBytes("alice") > 0L);
    assertTrue(manifestPort.findByFileId(activeFileId).isPresent());

    service.delete("alice", created.entryId());

    // Quota was released; manifest and placements were purged.
    assertEquals(0L, quotaPort.usedBytes("alice"));
    assertTrue(manifestPort.findByFileId(activeFileId).isEmpty());
    assertTrue(placementPort.findByFileId(activeFileId).isEmpty());
  }

  private static String sha256HexOf(final byte[] payload) {
    try {
      final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of().formatHex(digest.digest(payload));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  /** In-memory recording remote client used by the distribution-mode delete test. */
  private static final class RecordingRemoteClient implements RemoteFragmentDistributionClientPort {
    private final Map<String, byte[]> stores = new HashMap<>();

    @Override
    public void storeFragment(
        final String custodianBaseUrl,
        final String fragmentId,
        final String agreementId,
        final byte[] payload,
        final String checksumAlgorithm,
        final String checksumHex,
        final Long custodySeconds) {
      stores.put(custodianBaseUrl + "::" + fragmentId, payload.clone());
    }

    @Override
    public byte[] fetchFragment(final String custodianBaseUrl, final String fragmentId) {
      final byte[] payload = stores.get(custodianBaseUrl + "::" + fragmentId);
      if (payload == null) {
        throw new IllegalStateException("fragment not stored: " + fragmentId);
      }
      return payload.clone();
    }
  }

  // ---------- RENAME/MOVE replication to tutor ----------

  @Test
  void patch_callsTutorBeforePersistingLocally() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneOffset.UTC);
    final var remoteStore =
        new es.ual.node.filesystem.adapters.out.memory.InMemoryRemoteFileManifestStoreAdapter();
    final FileSystemService service =
        new FileSystemService(port, clock, null, null, remoteStore, "http://tutor:8080");

    final FsEntry created =
        service.upsert(
            new FsUpsertRequest(
                "alice",
                null,
                "/docs/c.txt",
                FsEntryType.FILE,
                3L,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "11111111-1111-1111-1111-111111111111",
                false));

    final FsEntry moved =
        service.patch(new FsPatchRequest("alice", created.entryId(), "/archive/c.txt"));

    assertEquals("/archive/c.txt", moved.path());
    final var record = remoteStore.findPathUpdate("http://tutor:8080", created.fileId());
    assertEquals("/alice/archive", record.newDirectoryPath());
    assertEquals("c.txt", record.newOriginalFileName());
  }

  /**
   * Mover un archivo a la raíz hacía que `extractParentPathForManifest` devolviera {@code "/"} y la
   * concatenación con el username produjera {@code "/<user>/"} con trailing slash, que el regex
   * {@code DIRECTORY_PATH_PATTERN} del tutor rechaza con 400. Mismo patrón root-aware que ya
   * aplicaba {@code FileContentDistributionService} en el upload inicial.
   */
  @Test
  void patch_replicatesRootDirectoryPathWithoutTrailingSlash() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneOffset.UTC);
    final var remoteStore =
        new es.ual.node.filesystem.adapters.out.memory.InMemoryRemoteFileManifestStoreAdapter();
    final FileSystemService service =
        new FileSystemService(port, clock, null, null, remoteStore, "http://tutor:8080");

    final FsEntry created =
        service.upsert(
            new FsUpsertRequest(
                "alice",
                null,
                "/a/Simulator Screenshot.png",
                FsEntryType.FILE,
                3L,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "22222222-2222-2222-2222-222222222222",
                false));

    final FsEntry moved =
        service.patch(new FsPatchRequest("alice", created.entryId(), "/Simulator Screenshot.png"));

    assertEquals("/Simulator Screenshot.png", moved.path());
    final var record = remoteStore.findPathUpdate("http://tutor:8080", created.fileId());
    assertEquals("/alice", record.newDirectoryPath());
    assertEquals("Simulator Screenshot.png", record.newOriginalFileName());
  }

  @Test
  void patch_doesNotPersistLocallyWhenTutorReplicationFails() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneOffset.UTC);
    final var remoteStore =
        new es.ual.node.filesystem.adapters.out.memory.InMemoryRemoteFileManifestStoreAdapter();
    final FileSystemService service =
        new FileSystemService(port, clock, null, null, remoteStore, "http://tutor:8080");

    final FsEntry created =
        service.upsert(
            new FsUpsertRequest(
                "alice",
                null,
                "/docs/c.txt",
                FsEntryType.FILE,
                3L,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "11111111-1111-1111-1111-111111111111",
                false));

    remoteStore.simulateUpdatePathFailure(true);

    assertThrows(
        TutorManifestReplicationException.class,
        () -> service.patch(new FsPatchRequest("alice", created.entryId(), "/archive/c.txt")));

    // Rollback verdadero: el path local sigue siendo el viejo.
    final FsEntry stillThere =
        port.findByUsernameAndEntryId("alice", created.entryId()).orElseThrow();
    assertEquals("/docs/c.txt", stillThere.path());
    assertEquals(1L, stillThere.version());
  }

  @Test
  void patch_succeedsWithoutTutorWiringForLegacySetups() {
    // Sin remoteFileManifestStorePort wired, patch funciona como antes (sólo cambio local).
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneOffset.UTC);
    final FileSystemService service = new FileSystemService(port, clock);

    final FsEntry created =
        service.upsert(
            new FsUpsertRequest(
                "alice",
                null,
                "/docs/c.txt",
                FsEntryType.FILE,
                3L,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "11111111-1111-1111-1111-111111111111",
                false));

    final FsEntry moved =
        service.patch(new FsPatchRequest("alice", created.entryId(), "/archive/c.txt"));

    assertEquals("/archive/c.txt", moved.path());
  }

  // ---------- moveSubtree ----------

  private static FsEntry seedFileEntry(
      final InMemoryFsEntryPort port, final String path, final String fileId) {
    final String entryId = UUID.randomUUID().toString();
    final FsEntry entry =
        new FsEntry(
            entryId,
            "alice",
            path,
            FsEntryType.FILE,
            10L,
            "f".repeat(64),
            fileId,
            1L,
            Instant.parse("2026-05-03T09:00:00Z"),
            false);
    port.save(entry);
    return entry;
  }

  private static FsEntry seedDirEntry(final InMemoryFsEntryPort port, final String path) {
    final String entryId = UUID.randomUUID().toString();
    final FsEntry entry =
        new FsEntry(
            entryId,
            "alice",
            path,
            FsEntryType.DIRECTORY,
            0L,
            null,
            null,
            1L,
            Instant.parse("2026-05-03T09:00:00Z"),
            false);
    port.save(entry);
    return entry;
  }

  @Test
  void moveSubtree_movesAllChildrenAndCallsTutorOnce() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneOffset.UTC);
    final var remoteStore =
        new es.ual.node.filesystem.adapters.out.memory.InMemoryRemoteFileManifestStoreAdapter();
    final FileSystemService service =
        new FileSystemService(port, clock, null, null, remoteStore, "http://tutor:8080");

    seedDirEntry(port, "/A");
    final FsEntry foto = seedFileEntry(port, "/A/foto.jpg", "11111111-1111-1111-1111-111111111111");
    seedDirEntry(port, "/A/sub");
    final FsEntry doc =
        seedFileEntry(port, "/A/sub/doc.pdf", "22222222-2222-2222-2222-222222222222");
    final FsEntry img =
        seedFileEntry(port, "/A/sub/img.png", "33333333-3333-3333-3333-333333333333");

    final var moved = service.moveSubtree("alice", "/A", "/B");

    // 5 entries movidas (2 DIRECTORY + 3 FILE) en orden hojas-primero.
    assertEquals(5, moved.size());
    // Los 3 FILE movidos.
    assertEquals(
        "/B/foto.jpg", port.findByUsernameAndEntryId("alice", foto.entryId()).orElseThrow().path());
    assertEquals(
        "/B/sub/doc.pdf",
        port.findByUsernameAndEntryId("alice", doc.entryId()).orElseThrow().path());
    assertEquals(
        "/B/sub/img.png",
        port.findByUsernameAndEntryId("alice", img.entryId()).orElseThrow().path());
    // El tutor recibió EXACTAMENTE 1 invocación bulk con los 3 FILE.
    assertEquals(1, remoteStore.bulkUpdateInvocationCount());
    final var bulk = remoteStore.findBulkUpdates("http://tutor:8080").get(0);
    assertEquals(3, bulk.entries().size());
  }

  @Test
  void moveSubtree_rollsBackLocalWhenTutorFails() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneOffset.UTC);
    final var remoteStore =
        new es.ual.node.filesystem.adapters.out.memory.InMemoryRemoteFileManifestStoreAdapter();
    final FileSystemService service =
        new FileSystemService(port, clock, null, null, remoteStore, "http://tutor:8080");

    seedDirEntry(port, "/A");
    final FsEntry foto = seedFileEntry(port, "/A/foto.jpg", "11111111-1111-1111-1111-111111111111");
    final FsEntry doc = seedFileEntry(port, "/A/doc.pdf", "22222222-2222-2222-2222-222222222222");
    remoteStore.simulateUpdatePathBulkFailure(true);

    assertThrows(
        TutorManifestReplicationException.class, () -> service.moveSubtree("alice", "/A", "/B"));

    // Rollback verdadero: ningún path cambió.
    assertEquals(
        "/A/foto.jpg", port.findByUsernameAndEntryId("alice", foto.entryId()).orElseThrow().path());
    assertEquals(
        "/A/doc.pdf", port.findByUsernameAndEntryId("alice", doc.entryId()).orElseThrow().path());
  }

  @Test
  void moveSubtree_rejectsTargetWhenAnyChildPathInUse() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneOffset.UTC);
    final FileSystemService service = new FileSystemService(port, clock);

    seedDirEntry(port, "/A");
    seedFileEntry(port, "/A/foto.jpg", "11111111-1111-1111-1111-111111111111");
    // Out-of-subtree entry already at the target child path.
    seedFileEntry(port, "/B/foto.jpg", "44444444-4444-4444-4444-444444444444");

    assertThrows(FsPathConflictException.class, () -> service.moveSubtree("alice", "/A", "/B"));
  }

  @Test
  void moveSubtree_rejectsToPathDescendantOfFromPath() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneOffset.UTC);
    final FileSystemService service = new FileSystemService(port, clock);

    seedDirEntry(port, "/A");
    seedFileEntry(port, "/A/foto.jpg", "11111111-1111-1111-1111-111111111111");

    assertThrows(FsPathConflictException.class, () -> service.moveSubtree("alice", "/A", "/A/sub"));
  }

  // ---------- deleteSubtree ----------

  @Test
  void deleteSubtree_marksAllEntriesAsTombstonesAndCallsTutorOnce() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-03T11:00:00Z"), ZoneOffset.UTC);
    final var remoteStore =
        new es.ual.node.filesystem.adapters.out.memory.InMemoryRemoteFileManifestStoreAdapter();
    final FileSystemService service =
        new FileSystemService(port, clock, null, null, remoteStore, "http://tutor:8080");

    seedDirEntry(port, "/X");
    final FsEntry foo = seedFileEntry(port, "/X/foo.jpg", "11111111-1111-1111-1111-111111111111");
    seedDirEntry(port, "/X/sub");
    final FsEntry bar =
        seedFileEntry(port, "/X/sub/bar.pdf", "22222222-2222-2222-2222-222222222222");
    final FsEntry baz =
        seedFileEntry(port, "/X/sub/baz.png", "33333333-3333-3333-3333-333333333333");

    final var deleted = service.deleteSubtree("alice", "/X");

    // 5 tombstones (2 DIR + 3 FILE).
    assertEquals(5, deleted.size());
    // Todos marcados deleted=true; FILE entries con fileId=null y path manglado.
    assertTrue(port.findByUsernameAndEntryId("alice", foo.entryId()).orElseThrow().deleted());
    assertTrue(port.findByUsernameAndEntryId("alice", bar.entryId()).orElseThrow().deleted());
    assertTrue(port.findByUsernameAndEntryId("alice", baz.entryId()).orElseThrow().deleted());
    assertEquals(
        null, port.findByUsernameAndEntryId("alice", foo.entryId()).orElseThrow().fileId());
    // Tutor recibió EXACTAMENTE 1 invocación bulk delete con los 3 fileIds.
    assertEquals(1, remoteStore.bulkDeleteInvocationCount());
    final var bulk = remoteStore.findBulkDeletes("http://tutor:8080").get(0);
    assertEquals(3, bulk.fileIds().size());
  }

  @Test
  void deleteSubtree_persistsLocallyEvenWhenTutorFails() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-03T11:00:00Z"), ZoneOffset.UTC);
    final var remoteStore =
        new es.ual.node.filesystem.adapters.out.memory.InMemoryRemoteFileManifestStoreAdapter();
    final FileSystemService service =
        new FileSystemService(port, clock, null, null, remoteStore, "http://tutor:8080");

    seedDirEntry(port, "/X");
    final FsEntry foo = seedFileEntry(port, "/X/foo.jpg", "11111111-1111-1111-1111-111111111111");
    remoteStore.simulateBulkDeleteFailure(true);

    // No throw — best-effort.
    final var deleted = service.deleteSubtree("alice", "/X");

    assertEquals(2, deleted.size());
    // Local tombstones persistieron pese al fallo del tutor.
    assertTrue(port.findByUsernameAndEntryId("alice", foo.entryId()).orElseThrow().deleted());
  }

  @Test
  void deleteSubtree_throwsWhenSubtreeRootNotFound() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-03T11:00:00Z"), ZoneOffset.UTC);
    final FileSystemService service = new FileSystemService(port, clock);

    assertThrows(NoSuchElementException.class, () -> service.deleteSubtree("alice", "/ghost"));
  }

  @Test
  void deleteSubtree_handlesEmptyDirectory() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-03T11:00:00Z"), ZoneOffset.UTC);
    final var remoteStore =
        new es.ual.node.filesystem.adapters.out.memory.InMemoryRemoteFileManifestStoreAdapter();
    final FileSystemService service =
        new FileSystemService(port, clock, null, null, remoteStore, "http://tutor:8080");

    seedDirEntry(port, "/empty");

    final var deleted = service.deleteSubtree("alice", "/empty");

    assertEquals(1, deleted.size());
    assertTrue(deleted.get(0).deleted());
    // No FILE entries → no bulk DELETE al tutor.
    assertEquals(0, remoteStore.bulkDeleteInvocationCount());
  }

  @Test
  void moveSubtree_handlesEmptyDirectory() {
    final InMemoryFsEntryPort port = new InMemoryFsEntryPort();
    final Clock clock = Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneOffset.UTC);
    final var remoteStore =
        new es.ual.node.filesystem.adapters.out.memory.InMemoryRemoteFileManifestStoreAdapter();
    final FileSystemService service =
        new FileSystemService(port, clock, null, null, remoteStore, "http://tutor:8080");

    final FsEntry dir = seedDirEntry(port, "/A");

    final var moved = service.moveSubtree("alice", "/A", "/B");

    assertEquals(1, moved.size());
    assertEquals("/B", moved.get(0).path());
    assertEquals("/B", port.findByUsernameAndEntryId("alice", dir.entryId()).orElseThrow().path());
    // No FILEs en el subtree → no bulk al tutor.
    assertEquals(0, remoteStore.bulkUpdateInvocationCount());
  }
}
