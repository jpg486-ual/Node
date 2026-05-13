package es.ual.node.filesystem.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import es.ual.node.filesystem.adapters.out.memory.InMemoryFileManifestPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFileUploadSessionPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFragmentPlacementPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFsEntryPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFsFileContentPort;
import es.ual.node.filesystem.domain.FileUploadSessionStatus;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.filesystem.ports.out.FsUploadStagingPort;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FileUploadSessionService}. */
class FileUploadSessionServiceTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"), ZoneOffset.UTC);

  private InMemoryFsEntryPort fsEntryPort;
  private InMemoryFsFileContentPort fsFileContentPort;
  private InMemoryFileUploadSessionPort uploadSessionPort;
  private TestStagingPort stagingPort;
  private FileUploadSessionService service;

  @BeforeEach
  void setUp() {
    fsEntryPort = new InMemoryFsEntryPort();
    fsFileContentPort = new InMemoryFsFileContentPort();
    uploadSessionPort = new InMemoryFileUploadSessionPort();
    stagingPort = new TestStagingPort();
    service =
        new FileUploadSessionService(
            fsEntryPort, fsFileContentPort, uploadSessionPort, stagingPort, FIXED_CLOCK);
  }

  @Test
  void create_returnsOpenSessionForActiveFileEntry() {
    final FsEntry entry = persistFileEntry("alice", "/notes.txt", "hello world".getBytes());

    final FileUploadSessionView view = service.create("alice", entry.entryId());

    assertThat(view.status()).isEqualTo(FileUploadSessionStatus.OPEN);
    assertThat(view.entryId()).isEqualTo(entry.entryId());
    assertThat(view.expectedSizeBytes()).isEqualTo(entry.sizeBytes());
    assertThat(view.uploadedBytes()).isZero();
  }

  @Test
  void create_failsForDirectoryEntry() {
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

    assertThatIllegalArgumentException().isThrownBy(() -> service.create("alice", entryId));
  }

  @Test
  void create_failsForDeletedEntry() {
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
        .isThrownBy(() -> service.create("alice", entryId));
  }

  @Test
  void create_failsForUnknownEntry() {
    assertThatExceptionOfType(NoSuchElementException.class)
        .isThrownBy(() -> service.create("alice", UUID.randomUUID().toString()));
  }

  @Test
  void appendChunk_acceptsContiguousOffsetZero() {
    final FsEntry entry = persistFileEntry("alice", "/notes.txt", "hello".getBytes());
    final FileUploadSessionView session = service.create("alice", entry.entryId());

    final FileUploadSessionView updated =
        service.appendChunk("alice", session.sessionId(), 0L, "hello".getBytes());

    assertThat(updated.uploadedBytes()).isEqualTo(5L);
    assertThat(stagingPort.bytesFor("alice", session.sessionId())).isEqualTo("hello".getBytes());
  }

  @Test
  void appendChunk_failsWhenOffsetMismatchesUploadedBytes() {
    final FsEntry entry = persistFileEntry("alice", "/notes.txt", "hello".getBytes());
    final FileUploadSessionView session = service.create("alice", entry.entryId());

    assertThatExceptionOfType(FsContentConflictException.class)
        .isThrownBy(() -> service.appendChunk("alice", session.sessionId(), 1L, "hi".getBytes()));
  }

  @Test
  void appendChunk_failsWhenChunkExceedsExpectedSize() {
    final FsEntry entry = persistFileEntry("alice", "/notes.txt", "hi".getBytes());
    final FileUploadSessionView session = service.create("alice", entry.entryId());

    assertThatExceptionOfType(FsContentConflictException.class)
        .isThrownBy(
            () -> service.appendChunk("alice", session.sessionId(), 0L, "abcdef".getBytes()));
  }

  @Test
  void complete_promotesContentWhenSizeAndChecksumMatch() {
    final byte[] content = "complete-content".getBytes();
    final FsEntry entry = persistFileEntry("alice", "/notes.txt", content);
    final FileUploadSessionView session = service.create("alice", entry.entryId());
    service.appendChunk("alice", session.sessionId(), 0L, content);

    final FileContentUploadResult result = service.complete("alice", session.sessionId());

    assertThat(result.entryId()).isEqualTo(entry.entryId());
    assertThat(result.sizeBytes()).isEqualTo(content.length);
    assertThat(result.checksum()).isEqualTo(sha256Hex(content));
    assertThat(fsFileContentPort.find("alice", entry.entryId())).contains(content);
    assertThat(stagingPort.bytesFor("alice", session.sessionId())).isNull();
    assertThat(
            uploadSessionPort
                .findByUsernameAndSessionId("alice", session.sessionId())
                .orElseThrow()
                .status())
        .isEqualTo(FileUploadSessionStatus.COMPLETED);
  }

  @Test
  void complete_failsOnSizeMismatch() {
    final FsEntry entry = persistFileEntry("alice", "/notes.txt", "size-five".getBytes());
    final FileUploadSessionView session = service.create("alice", entry.entryId());
    service.appendChunk("alice", session.sessionId(), 0L, "abc".getBytes());

    assertThatExceptionOfType(FsContentConflictException.class)
        .isThrownBy(() -> service.complete("alice", session.sessionId()));
  }

  @Test
  void complete_failsOnChecksumMismatch() {
    final byte[] declaredContent = "declared-bytes".getBytes();
    final FsEntry entry = persistFileEntry("alice", "/notes.txt", declaredContent);
    final FileUploadSessionView session = service.create("alice", entry.entryId());
    final byte[] differentBytesSameLength = new byte[declaredContent.length];
    for (int i = 0; i < differentBytesSameLength.length; i++) {
      differentBytesSameLength[i] = (byte) ('z');
    }
    service.appendChunk("alice", session.sessionId(), 0L, differentBytesSameLength);

    assertThatExceptionOfType(FsContentConflictException.class)
        .isThrownBy(() -> service.complete("alice", session.sessionId()));
  }

  // ---------- chunked complete delegates to RS distribution ----------

  @Test
  void complete_delegatesToDistributionService_whenAvailable() {
    final byte[] content = "chunked-rs-content".getBytes();
    final ChunkedDistributionFixture fixture =
        new ChunkedDistributionFixture(fsEntryPort, FIXED_CLOCK);
    final FileUploadSessionService distributionAware =
        new FileUploadSessionService(
            fsEntryPort,
            fsFileContentPort,
            uploadSessionPort,
            stagingPort,
            FIXED_CLOCK,
            fixture.orchestrator);

    final FsEntry entry = persistFileEntry("alice", "/chunked.bin", content);
    final FileUploadSessionView session = distributionAware.create("alice", entry.entryId());
    distributionAware.appendChunk("alice", session.sessionId(), 0L, content);

    final FileContentUploadResult result = distributionAware.complete("alice", session.sessionId());

    assertThat(result.entryId()).isEqualTo(entry.entryId());
    assertThat(result.sizeBytes()).isEqualTo(content.length);
    // Local-blob adapter MUST NOT receive the bytes when distribution is wired.
    assertThat(fsFileContentPort.find("alice", entry.entryId())).isEmpty();
    // Lee el fileId post-upload de la FsEntry persistida (distribution genera un fileId
    // fresco por upload y lo sincroniza vía withFileId).
    final String activeFileId =
        fsEntryPort.findByUsernameAndEntryId("alice", entry.entryId()).orElseThrow().fileId();
    assertThat(fixture.manifestPort.findByFileId(activeFileId)).isPresent();
    assertThat(fixture.placementPort.findByFileId(activeFileId)).hasSize(3);
    // Staging area cleaned up.
    assertThat(stagingPort.bytesFor("alice", session.sessionId())).isNull();
    // Session marked COMPLETED.
    assertThat(
            uploadSessionPort
                .findByUsernameAndSessionId("alice", session.sessionId())
                .orElseThrow()
                .status())
        .isEqualTo(FileUploadSessionStatus.COMPLETED);
  }

  @Test
  void complete_failsWithChecksumMismatchInDistribution() {
    // The orchestrator validates SHA-256 against entry.checksum(); a chunked upload that
    // delivered different bytes than declared at create-time must surface as a 409 conflict.
    final byte[] declared = "declared-bytes".getBytes();
    final ChunkedDistributionFixture fixture =
        new ChunkedDistributionFixture(fsEntryPort, FIXED_CLOCK);
    final FileUploadSessionService distributionAware =
        new FileUploadSessionService(
            fsEntryPort,
            fsFileContentPort,
            uploadSessionPort,
            stagingPort,
            FIXED_CLOCK,
            fixture.orchestrator);

    final FsEntry entry = persistFileEntry("alice", "/chunked.bin", declared);
    final FileUploadSessionView session = distributionAware.create("alice", entry.entryId());
    final byte[] tampered = new byte[declared.length];
    for (int i = 0; i < tampered.length; i++) {
      tampered[i] = (byte) 'z';
    }
    distributionAware.appendChunk("alice", session.sessionId(), 0L, tampered);

    assertThatExceptionOfType(FsContentConflictException.class)
        .isThrownBy(() -> distributionAware.complete("alice", session.sessionId()));
  }

  @Test
  void appendChunk_failsWhenSessionNotOpen() {
    final byte[] content = "complete-it".getBytes();
    final FsEntry entry = persistFileEntry("alice", "/notes.txt", content);
    final FileUploadSessionView session = service.create("alice", entry.entryId());
    service.appendChunk("alice", session.sessionId(), 0L, content);
    service.complete("alice", session.sessionId());

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.appendChunk("alice", session.sessionId(), 0L, "more".getBytes()));
  }

  @Test
  void get_returnsCurrentViewWithUploadedBytes() {
    final FsEntry entry = persistFileEntry("alice", "/notes.txt", "abcdef".getBytes());
    final FileUploadSessionView session = service.create("alice", entry.entryId());
    service.appendChunk("alice", session.sessionId(), 0L, "abc".getBytes());

    final FileUploadSessionView view = service.get("alice", session.sessionId());

    assertThat(view.uploadedBytes()).isEqualTo(3L);
    assertThat(view.status()).isEqualTo(FileUploadSessionStatus.OPEN);
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

  /** Minimal in-memory staging port stub for unit tests. */
  private static final class TestStagingPort implements FsUploadStagingPort {
    private final Map<String, byte[]> store = new LinkedHashMap<>();

    @Override
    public void reset(final String username, final String sessionId) {
      store.remove(key(username, sessionId));
    }

    @Override
    public void append(
        final String username, final String sessionId, final long offset, final byte[] chunk) {
      final String key = key(username, sessionId);
      final byte[] current = store.getOrDefault(key, new byte[0]);
      if (current.length != offset) {
        throw new IllegalStateException("staging offset mismatch");
      }
      final byte[] combined = new byte[current.length + chunk.length];
      System.arraycopy(current, 0, combined, 0, current.length);
      System.arraycopy(chunk, 0, combined, current.length, chunk.length);
      store.put(key, combined);
    }

    @Override
    public Optional<byte[]> readAll(final String username, final String sessionId) {
      return Optional.ofNullable(store.get(key(username, sessionId)));
    }

    @Override
    public Optional<java.io.InputStream> openInputStream(
        final String username, final String sessionId) {
      final byte[] bytes = store.get(key(username, sessionId));
      return bytes == null
          ? Optional.empty()
          : Optional.of(new java.io.ByteArrayInputStream(bytes));
    }

    @Override
    public void delete(final String username, final String sessionId) {
      store.remove(key(username, sessionId));
    }

    byte[] bytesFor(final String username, final String sessionId) {
      return store.get(key(username, sessionId));
    }

    private static String key(final String username, final String sessionId) {
      return username + "::" + sessionId;
    }
  }

  /**
   * Wires a real {@link FileContentDistributionService} backed by in-memory adapters and a
   * recording remote client (mirrors the fixture used by FileContentServiceTest).
   */
  private static final class ChunkedDistributionFixture {
    static final List<String> CUSTODIANS =
        List.of("http://node1:8080", "http://node2:8080", "http://node3:8080");

    final InMemoryFileManifestPort manifestPort = new InMemoryFileManifestPort();
    final InMemoryFragmentPlacementPort placementPort = new InMemoryFragmentPlacementPort();
    final RecordingRemoteClient remoteClient = new RecordingRemoteClient();
    final InMemoryUserAccountPort accountPort = new InMemoryUserAccountPort();
    final InMemoryUserQuotaPort quotaPort;
    final FileContentDistributionService orchestrator;

    ChunkedDistributionFixture(final InMemoryFsEntryPort entryPort, final Clock clock) {
      accountPort.save(new UserAccount("alice", "hash", 100, UserRole.END_USER, Instant.EPOCH));
      quotaPort = new InMemoryUserQuotaPort(accountPort);
      final InMemoryRsCodecAdapter rsCodec = new InMemoryRsCodecAdapter();
      // Ctor con entryPort para que el upload pipeline sincronice FsEntry.fileId con el
      // fileId generado.
      orchestrator =
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
              CUSTODIANS,
              null,
              null,
              entryPort);
    }
  }

  /** Recording remote HTTP client fake used by the chunked-distribution integration test. */
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
