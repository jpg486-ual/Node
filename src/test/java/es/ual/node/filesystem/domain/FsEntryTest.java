package es.ual.node.filesystem.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FsEntry}, focused on fileId invariants. */
class FsEntryTest {

  private static final String ENTRY_ID = "e1";
  private static final String USERNAME = "alice";
  private static final String PATH = "/docs/file.txt";
  private static final String CHECKSUM = "0".repeat(64);
  private static final String FILE_ID = "0d7f64c2-97cc-4400-a2a3-b3af056f85a1";
  private static final Instant NOW = Instant.parse("2026-04-27T10:00:00Z");

  @Test
  void shouldCreateActiveFileEntryWithFileId() {
    FsEntry entry =
        new FsEntry(
            ENTRY_ID, USERNAME, PATH, FsEntryType.FILE, 4L, CHECKSUM, FILE_ID, 1L, NOW, false);

    assertEquals(FILE_ID, entry.fileId());
  }

  @Test
  void shouldRejectActiveFileEntryWithoutFileId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FsEntry(
                ENTRY_ID, USERNAME, PATH, FsEntryType.FILE, 4L, CHECKSUM, null, 1L, NOW, false));
  }

  @Test
  void shouldRejectActiveFileEntryWithBlankFileId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FsEntry(
                ENTRY_ID, USERNAME, PATH, FsEntryType.FILE, 4L, CHECKSUM, "  ", 1L, NOW, false));
  }

  @Test
  void shouldAcceptDeletedFileEntryWithoutFileId() {
    FsEntry entry =
        new FsEntry(ENTRY_ID, USERNAME, PATH, FsEntryType.FILE, 4L, null, null, 1L, NOW, true);

    assertNull(entry.fileId());
  }

  @Test
  void shouldAcceptDirectoryEntryWithoutFileId() {
    FsEntry entry =
        new FsEntry(
            ENTRY_ID, USERNAME, "/folder", FsEntryType.DIRECTORY, 0L, null, null, 1L, NOW, false);

    assertNull(entry.fileId());
  }

  @Test
  void shouldRejectDirectoryEntryWithFileId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FsEntry(
                ENTRY_ID,
                USERNAME,
                "/folder",
                FsEntryType.DIRECTORY,
                0L,
                null,
                FILE_ID,
                1L,
                NOW,
                false));
  }

  @Test
  void shouldTrimFileIdForActiveFileEntry() {
    FsEntry entry =
        new FsEntry(
            ENTRY_ID,
            USERNAME,
            PATH,
            FsEntryType.FILE,
            4L,
            CHECKSUM,
            "  " + FILE_ID + "  ",
            1L,
            NOW,
            false);

    assertEquals(FILE_ID, entry.fileId());
  }
}
