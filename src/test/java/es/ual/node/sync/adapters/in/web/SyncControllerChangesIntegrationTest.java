package es.ual.node.sync.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.ual.node.filesystem.application.FileSystemService;
import es.ual.node.filesystem.application.FsUpsertRequest;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.userregistration.adapters.in.web.ClientAuthIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** Integration tests for {@code GET /sync/changes}. */
class SyncControllerChangesIntegrationTest extends ClientAuthIntegrationTestBase {

  @Autowired private FileSystemService fileSystemService;
  @Autowired private FsEntryPort fsEntryPort;

  /**
   * Upsert deja el FILE con contentUploaded=false (oculto en listings). Para tests del feed sync
   * marcamos contentUploaded=true vía el port directo, simulando el fin del pipeline de
   * distribución (que en producción lo hace {@code
   * FileContentDistributionService.distributeUploadStreaming}).
   */
  private FsEntry upsertReady(final FsUpsertRequest request) {
    final FsEntry created = fileSystemService.upsert(request);
    if (created.entryType() == FsEntryType.FILE && !created.deleted()) {
      final FsEntry ready = created.withContentUploaded(true);
      fsEntryPort.save(ready);
      return ready;
    }
    return created;
  }

  @Test
  void getChanges_returnsEmptyDeltaForFreshUser() throws Exception {
    final String token = obtainToken("changes-fresh");

    mockMvc
        .perform(
            get("/sync/changes").header("Authorization", "Bearer " + token).param("since", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("changes-fresh"))
        .andExpect(jsonPath("$.cursor").exists())
        .andExpect(jsonPath("$.snapshotAt").exists())
        .andExpect(jsonPath("$.changes").isArray())
        .andExpect(jsonPath("$.changes.length()").value(0));
  }

  @Test
  void getChanges_returnsDeltaAfterEntryCreation() throws Exception {
    final String token = obtainToken("changes-with-entry");

    upsertReady(
        new FsUpsertRequest(
            "changes-with-entry",
            null,
            "/docs/plan.txt",
            FsEntryType.FILE,
            42L,
            "sha256:" + "0".repeat(64),
            java.util.UUID.randomUUID().toString(),
            false));

    mockMvc
        .perform(
            get("/sync/changes").header("Authorization", "Bearer " + token).param("since", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.changes[0].path").value("/docs/plan.txt"))
        .andExpect(jsonPath("$.changes[0].entryType").value("FILE"));
  }

  @Test
  void getChanges_filtersBySinceCursor() throws Exception {
    final String token = obtainToken("changes-cursor");

    upsertReady(
        new FsUpsertRequest(
            "changes-cursor",
            null,
            "/older.txt",
            FsEntryType.FILE,
            10L,
            "sha256:" + "0".repeat(64),
            java.util.UUID.randomUUID().toString(),
            false));

    final MvcResult firstResult =
        mockMvc
            .perform(
                get("/sync/changes").header("Authorization", "Bearer " + token).param("since", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changes.length()").value(1))
            .andReturn();
    final long cursorAfterFirst =
        objectMapper
            .readTree(firstResult.getResponse().getContentAsString())
            .get("cursor")
            .asLong();

    mockMvc
        .perform(
            get("/sync/changes")
                .header("Authorization", "Bearer " + token)
                .param("since", String.valueOf(cursorAfterFirst + 1L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(0));

    Thread.sleep(5);
    upsertReady(
        new FsUpsertRequest(
            "changes-cursor",
            null,
            "/newer.txt",
            FsEntryType.FILE,
            20L,
            "sha256:" + "0".repeat(64),
            java.util.UUID.randomUUID().toString(),
            false));

    mockMvc
        .perform(
            get("/sync/changes")
                .header("Authorization", "Bearer " + token)
                .param("since", String.valueOf(cursorAfterFirst + 1L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.changes[0].path").value("/newer.txt"));
  }

  @Test
  void getChanges_returnsBadRequestWhenSinceMissing() throws Exception {
    final String token = obtainToken("changes-no-since");

    mockMvc
        .perform(get("/sync/changes").header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getChanges_returnsBadRequestWhenSinceNonNumeric() throws Exception {
    final String token = obtainToken("changes-bad-since");

    mockMvc
        .perform(
            get("/sync/changes").header("Authorization", "Bearer " + token).param("since", "abc"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getChanges_returnsErrorContractWhenSinceNegative() throws Exception {
    final String token = obtainToken("changes-negative-since");

    mockMvc
        .perform(
            get("/sync/changes").header("Authorization", "Bearer " + token).param("since", "-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("SYNC_INVALID_SINCE"));
  }

  @Test
  void getChanges_returnsUnauthorizedWithoutToken() throws Exception {
    mockMvc.perform(get("/sync/changes").param("since", "0")).andExpect(status().isUnauthorized());
  }
}
