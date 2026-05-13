package es.ual.node.filesystem.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import es.ual.node.filesystem.adapters.out.memory.InMemoryFileManifestPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFragmentPlacementPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFsEntryPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFsFileContentPort;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.filesystem.ports.out.RemoteFragmentDistributionClientPort;
import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsCodecAdapter;
import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsIntegrityVerifier;
import es.ual.node.reedsolomon.domain.RsScheme;
import es.ual.node.userregistration.adapters.out.memory.InMemoryUserAccountPort;
import es.ual.node.userregistration.adapters.out.memory.InMemoryUserQuotaPort;
import es.ual.node.userregistration.domain.UserAccount;
import es.ual.node.userregistration.domain.UserRole;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FileContentService}. */
class FileContentServiceTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"), ZoneOffset.UTC);

  private InMemoryFsEntryPort fsEntryPort;
  private InMemoryFsFileContentPort fsFileContentPort;
  private FileContentService service;

  @BeforeEach
  void setUp() {
    fsEntryPort = new InMemoryFsEntryPort();
    fsFileContentPort = new InMemoryFsFileContentPort();
    service = new FileContentService(fsEntryPort, fsFileContentPort);
  }

  @Test
  void upload_storesContentWhenChecksumAndSizeMatch() {
    final byte[] content = "good-content".getBytes();
    final FsEntry entry = persistFileEntry("alice", "/notes.txt", content);

    final FileContentUploadResult result = service.upload("alice", entry.entryId(), content);

    assertThat(result.entryId()).isEqualTo(entry.entryId());
    assertThat(result.sizeBytes()).isEqualTo(content.length);
    assertThat(result.checksum()).isEqualTo(sha256Hex(content));
    assertThat(fsFileContentPort.find("alice", entry.entryId())).contains(content);
  }

  @Test
  void upload_failsOnSizeMismatch() {
    final byte[] declared = "declared-content".getBytes();
    final FsEntry entry = persistFileEntry("alice", "/notes.txt", declared);

    assertThatExceptionOfType(FsContentConflictException.class)
        .isThrownBy(() -> service.upload("alice", entry.entryId(), "shorter".getBytes()));
  }

  @Test
  void upload_failsOnChecksumMismatch() {
    final byte[] declared = "declared-content".getBytes();
    final FsEntry entry = persistFileEntry("alice", "/notes.txt", declared);
    final byte[] differentSameLength = new byte[declared.length];
    for (int i = 0; i < differentSameLength.length; i++) {
      differentSameLength[i] = (byte) 'z';
    }

    assertThatExceptionOfType(FsContentConflictException.class)
        .isThrownBy(() -> service.upload("alice", entry.entryId(), differentSameLength));
  }

  @Test
  void upload_failsForDirectoryEntry() {
    final String entryId = UUID.randomUUID().toString();
    fsEntryPort.save(
        new FsEntry(
            entryId,
            "alice",
            "/folder",
            FsEntryType.DIRECTORY,
            0L,
            null,
            null,
            1L,
            FIXED_CLOCK.instant(),
            false));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.upload("alice", entryId, new byte[] {1, 2}));
  }

  @Test
  void upload_failsForDeletedEntry() {
    final String entryId = UUID.randomUUID().toString();
    fsEntryPort.save(
        new FsEntry(
            entryId,
            "alice",
            "/notes.txt",
            FsEntryType.FILE,
            5L,
            sha256Hex("hello".getBytes()),
            UUID.randomUUID().toString(),
            1L,
            FIXED_CLOCK.instant(),
            true));

    assertThatExceptionOfType(NoSuchElementException.class)
        .isThrownBy(() -> service.upload("alice", entryId, "hello".getBytes()));
  }

  @Test
  void download_returnsContentAndChecksum() {
    final byte[] content = "downloadable".getBytes();
    final FsEntry entry = persistFileEntry("alice", "/notes.txt", content);
    service.upload("alice", entry.entryId(), content);

    final FileContentDownloadResult result = service.download("alice", entry.entryId());

    assertThat(result.entryId()).isEqualTo(entry.entryId());
    assertThat(result.content()).isEqualTo(content);
    assertThat(result.checksum()).isEqualTo(sha256Hex(content));
  }

  @Test
  void download_failsForUnknownEntry() {
    assertThatExceptionOfType(NoSuchElementException.class)
        .isThrownBy(() -> service.download("alice", UUID.randomUUID().toString()));
  }

  @Test
  void deleteContent_removesFinalStorage() {
    final byte[] content = "todelete".getBytes();
    final FsEntry entry = persistFileEntry("alice", "/notes.txt", content);
    service.upload("alice", entry.entryId(), content);

    service.deleteContent("alice", entry.entryId());

    assertThat(fsFileContentPort.find("alice", entry.entryId())).isEmpty();
  }

  // ---------- Distribution mode ----------

  @Test
  void upload_delegatesToDistributionService_whenAvailable() {
    final byte[] content = "rs-pipeline-content".getBytes();
    final DistributionFixture fixture = new DistributionFixture(fsEntryPort);
    final FileContentService distributionAware =
        new FileContentService(fsEntryPort, fsFileContentPort, fixture.orchestrator());
    final FsEntry entry = persistFileEntry("alice", "/dist.txt", content);

    distributionAware.upload("alice", entry.entryId(), content);

    // Local-blob adapter must NOT receive the bytes in distribution mode.
    assertThat(fsFileContentPort.find("alice", entry.entryId())).isEmpty();
    // Distribution genera un fileId fresco por upload — lo leemos de la FsEntry persistida
    // (sincronizada vía withFileId en el upload flow), no de la pre-upload entry.
    final String activeFileId =
        fsEntryPort.findByUsernameAndEntryId("alice", entry.entryId()).orElseThrow().fileId();
    assertThat(fixture.manifestPort.findByFileId(activeFileId)).isPresent();
    assertThat(fixture.placementPort.findByFileId(activeFileId)).hasSize(3);
    assertThat(fixture.remoteClient.stores).hasSize(3);
  }

  @Test
  void download_delegatesToDistributionService_whenAvailable() {
    final byte[] content = "reconstruct-this".getBytes();
    final DistributionFixture fixture = new DistributionFixture(fsEntryPort);
    final FileContentService distributionAware =
        new FileContentService(fsEntryPort, fsFileContentPort, fixture.orchestrator());
    final FsEntry entry = persistFileEntry("alice", "/dist.txt", content);
    distributionAware.upload("alice", entry.entryId(), content);

    final FileContentDownloadResult result = distributionAware.download("alice", entry.entryId());

    assertThat(result.content()).isEqualTo(content);
    assertThat(result.checksum()).isEqualTo(sha256Hex(content));
    // Local-blob adapter is never consulted in distribution mode.
    assertThat(fsFileContentPort.find("alice", entry.entryId())).isEmpty();
  }

  @Test
  void uploadStreaming_delegatesToDistributionService() {
    final byte[] content = "rs-streaming-content".getBytes();
    final DistributionFixture fixture = new DistributionFixture(fsEntryPort);
    final FileContentService distributionAware =
        new FileContentService(fsEntryPort, fsFileContentPort, fixture.orchestrator());
    final FsEntry entry = persistFileEntry("alice", "/dist.txt", content);

    distributionAware.uploadStreaming(
        "alice", entry.entryId(), new java.io.ByteArrayInputStream(content), content.length);

    // Local-blob adapter must NOT receive the bytes in distribution mode.
    assertThat(fsFileContentPort.find("alice", entry.entryId())).isEmpty();
    // Lee el fileId post-upload de la FsEntry persistida.
    final String activeFileId =
        fsEntryPort.findByUsernameAndEntryId("alice", entry.entryId()).orElseThrow().fileId();
    assertThat(fixture.manifestPort.findByFileId(activeFileId)).isPresent();
    assertThat(fixture.placementPort.findByFileId(activeFileId)).hasSize(3);
  }

  @Test
  void downloadStreaming_writesDirectlyToOutputStream() {
    final byte[] content = "stream-me".getBytes();
    final DistributionFixture fixture = new DistributionFixture(fsEntryPort);
    final FileContentService distributionAware =
        new FileContentService(fsEntryPort, fsFileContentPort, fixture.orchestrator());
    final FsEntry entry = persistFileEntry("alice", "/dist.txt", content);
    distributionAware.uploadStreaming(
        "alice", entry.entryId(), new java.io.ByteArrayInputStream(content), content.length);

    final java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
    final String checksum = distributionAware.downloadStreaming("alice", entry.entryId(), sink);

    assertThat(sink.toByteArray()).isEqualTo(content);
    assertThat(checksum).isEqualTo(sha256Hex(content));
  }

  @Test
  void preflight_returnsPeersReconstruct_whenCatalogConsistent() {
    final byte[] content = "preflight-happy".getBytes();
    final DistributionFixture fixture = new DistributionFixture(fsEntryPort);
    final FileContentService distributionAware =
        new FileContentService(fsEntryPort, fsFileContentPort, fixture.orchestrator());
    final FsEntry entry = persistFileEntry("alice", "/dist.txt", content);
    distributionAware.upload("alice", entry.entryId(), content);

    final FileContentService.DownloadSource source =
        distributionAware.preflightDownload("alice", entry.entryId());

    assertThat(source).isEqualTo(FileContentService.DownloadSource.PEERS_RECONSTRUCT);
  }

  @Test
  void preflight_throwsInconsistentPlacement_whenDuplicateRowExists() {
    // El preflight lanza antes de tocar response, así el controller responde 503.
    final byte[] content = "preflight-corrupt".getBytes();
    final DistributionFixture fixture = new DistributionFixture(fsEntryPort);
    final FileContentService distributionAware =
        new FileContentService(fsEntryPort, fsFileContentPort, fixture.orchestrator());
    final FsEntry entry = persistFileEntry("alice", "/dist.txt", content);
    distributionAware.upload("alice", entry.entryId(), content);
    final String activeFileId =
        fsEntryPort.findByUsernameAndEntryId("alice", entry.entryId()).orElseThrow().fileId();

    // Inject a duplicate row for (blockIndex=0, fragmentIndex=0).
    final var first = fixture.placementPort.findByFileId(activeFileId).get(0);
    fixture.placementPort.save(
        new es.ual.node.filesystem.domain.FragmentPlacement(
            first.fileId(),
            "rs-fragment-fake-" + UUID.randomUUID(),
            first.blockIndex(),
            first.fragmentIndex(),
            first.parity(),
            first.custodianNodeId(),
            first.custodianBaseUrl(),
            first.agreementId(),
            first.fragmentChecksum(),
            999L,
            FIXED_CLOCK.instant()));

    assertThatExceptionOfType(InconsistentFragmentPlacementException.class)
        .isThrownBy(() -> distributionAware.preflightDownload("alice", entry.entryId()));
  }

  @Test
  void preflight_throwsIrrecoverable_whenManifestMissingEvenIfBlobPresent_distributionActive() {
    // Con distribución activa el fallback al blob local queda DESACTIVADO. Aunque exista
    // un blob remanente en disco (p.ej. depósito legacy), el preflight debe surfacear
    // FileIrrecoverableException para preservar el modelo "fragments-only nodes".
    final byte[] content = "blob-no-fallback".getBytes();
    final DistributionFixture fixture = new DistributionFixture(fsEntryPort);
    final FileContentService distributionAware =
        new FileContentService(fsEntryPort, fsFileContentPort, fixture.orchestrator());
    final FsEntry entry = persistFileEntry("alice", "/dist.txt", content);
    fsFileContentPort.save("alice", entry.entryId(), content);
    // entry.fileId() apunta a un fileId que NO está en manifestPort de la fixture.

    assertThatExceptionOfType(FileIrrecoverableException.class)
        .isThrownBy(() -> distributionAware.preflightDownload("alice", entry.entryId()));
  }

  @Test
  void preflight_throwsIrrecoverable_whenNeitherPathWorks() {
    final byte[] content = "no-path-content".getBytes();
    final DistributionFixture fixture = new DistributionFixture(fsEntryPort);
    final FileContentService distributionAware =
        new FileContentService(fsEntryPort, fsFileContentPort, fixture.orchestrator());
    // FsEntry vivo con fileId, pero no hay manifest en la fixture ni blob local.
    final FsEntry entry = persistFileEntry("alice", "/dist.txt", content);

    assertThatExceptionOfType(FileIrrecoverableException.class)
        .isThrownBy(() -> distributionAware.preflightDownload("alice", entry.entryId()));
  }

  @Test
  void deleteContent_skipsLocalAdapter_whenDistributionAvailable() {
    final DistributionFixture fixture = new DistributionFixture(fsEntryPort);
    final FileContentService distributionAware =
        new FileContentService(fsEntryPort, fsFileContentPort, fixture.orchestrator());
    fsFileContentPort.save("alice", "stale-blob", new byte[] {1});

    distributionAware.deleteContent("alice", "stale-blob");

    // The legacy blob remains untouched: cleanup is now FileSystemService.delete's job.
    assertThat(fsFileContentPort.find("alice", "stale-blob")).contains(new byte[] {1});
  }

  // ---------- Helpers ----------

  private FsEntry persistFileEntry(final String username, final String path, final byte[] content) {
    final String entryId = UUID.randomUUID().toString();
    final FsEntry entry =
        new FsEntry(
            entryId,
            username,
            path,
            FsEntryType.FILE,
            content.length,
            sha256Hex(content),
            UUID.randomUUID().toString(),
            1L,
            FIXED_CLOCK.instant(),
            false);
    fsEntryPort.save(entry);
    return entry;
  }

  private static String sha256Hex(final byte[] content) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  /**
   * Wires a real {@link FileContentDistributionService} backed by in-memory adapters and a
   * recording remote client. Used by the distribution-mode tests above to exercise the delegation
   * path end-to-end without spinning up Spring.
   */
  private static final class DistributionFixture {
    static final List<String> CUSTODIANS =
        List.of("http://node1:8080", "http://node2:8080", "http://node3:8080");

    final InMemoryFileManifestPort manifestPort = new InMemoryFileManifestPort();
    final InMemoryFragmentPlacementPort placementPort = new InMemoryFragmentPlacementPort();
    final RecordingRemoteClient remoteClient = new RecordingRemoteClient();
    final InMemoryUserAccountPort accountPort = new InMemoryUserAccountPort();
    final InMemoryUserQuotaPort quotaPort;
    final FileContentDistributionService orchestrator;

    DistributionFixture(final InMemoryFsEntryPort fsEntryPort) {
      accountPort.save(new UserAccount("alice", "hash", 100, UserRole.END_USER, Instant.EPOCH));
      quotaPort = new InMemoryUserQuotaPort(accountPort);
      final InMemoryRsCodecAdapter rsCodec = new InMemoryRsCodecAdapter();
      // Ctor con fsEntryPort cableado para que el upload pipeline sincronice FsEntry.fileId
      // con el fileId generado. Sin esto la FsEntry mantendría el fileId pre-upload y el
      // download (que lee entry.fileId()) daría 404 en reconstruct.
      orchestrator =
          new FileContentDistributionService(
              rsCodec,
              rsCodec,
              new InMemoryRsIntegrityVerifier(),
              manifestPort,
              placementPort,
              remoteClient,
              quotaPort,
              FIXED_CLOCK,
              new RsScheme(3, 2, 16),
              10L * 1024L * 1024L,
              Integer.MAX_VALUE,
              CUSTODIANS,
              null,
              null,
              fsEntryPort);
    }

    FileContentDistributionService orchestrator() {
      return orchestrator;
    }
  }

  /**
   * In-memory fake of the remote HTTP client. Stores fragments under {@code
   * <baseUrl>::<fragmentId>} and serves fetches back unless the baseUrl is marked unreachable.
   */
  private static final class RecordingRemoteClient implements RemoteFragmentDistributionClientPort {
    final Map<String, byte[]> stores = new HashMap<>();
    final Set<String> unreachableBaseUrls = new HashSet<>();

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
      if (unreachableBaseUrls.contains(custodianBaseUrl)) {
        throw new IllegalStateException("custodian unreachable: " + custodianBaseUrl);
      }
      final byte[] payload = stores.get(custodianBaseUrl + "::" + fragmentId);
      if (payload == null) {
        throw new IllegalStateException("fragment not stored: " + fragmentId);
      }
      return payload.clone();
    }
  }
}
