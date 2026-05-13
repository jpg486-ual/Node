package es.ual.node.filesystem.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.userregistration.adapters.in.web.ClientAuthIntegrationTestBase;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Integration tests for {@link FsTreeController}. */
class FsTreeControllerIntegrationTest extends ClientAuthIntegrationTestBase {

  @Autowired private FsEntryPort fsEntryPort;

  @Test
  void getTree_returnsEmptyForFreshUser() throws Exception {
    final String token = obtainToken("tree-fresh");

    mockMvc
        .perform(get("/fs/tree").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("tree-fresh"))
        .andExpect(jsonPath("$.entries").isArray())
        .andExpect(jsonPath("$.entries.length()").value(0));
  }

  @Test
  void getTree_returnsEntriesAfterUpsert() throws Exception {
    final String token = obtainToken("tree-with-entry");
    upsertFile("tree-with-entry", token, "/docs/plan.txt", "hello".getBytes());

    mockMvc
        .perform(get("/fs/tree").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries.length()").value(1))
        .andExpect(jsonPath("$.entries[0].path").value("/docs/plan.txt"));
  }

  @Test
  void getTree_filtersBySinceCursor() throws Exception {
    final String token = obtainToken("tree-cursor");
    upsertFile("tree-cursor", token, "/old.txt", "old".getBytes());

    final MvcResult firstResult =
        mockMvc
            .perform(get("/fs/tree").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    final long cursor =
        objectMapper
            .readTree(firstResult.getResponse().getContentAsString())
            .get("cursor")
            .asLong();

    mockMvc
        .perform(
            get("/fs/tree")
                .header("Authorization", "Bearer " + token)
                .param("sinceCursor", String.valueOf(cursor + 1L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries.length()").value(0));
  }

  @Test
  void postEntries_createsFileEntry() throws Exception {
    final String token = obtainToken("entries-file");
    final byte[] content = "abc".getBytes();
    final String checksum = sha256Hex(content);

    mockMvc
        .perform(
            post("/fs/entries")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "path":"/file.txt",
                      "entryType":"FILE",
                      "sizeBytes":3,
                      "checksum":"%s",
                      "deleted":false
                    }
                    """
                        .formatted(checksum)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/file.txt"))
        .andExpect(jsonPath("$.entryType").value("FILE"))
        .andExpect(jsonPath("$.sizeBytes").value(3))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void postEntries_createsDirectoryEntry() throws Exception {
    final String token = obtainToken("entries-directory");

    mockMvc
        .perform(
            post("/fs/entries")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "path":"/folder",
                      "entryType":"DIRECTORY",
                      "deleted":false
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/folder"))
        .andExpect(jsonPath("$.entryType").value("DIRECTORY"));
  }

  @Test
  void postEntries_returnsConflictOnDuplicatePath() throws Exception {
    final String token = obtainToken("entries-conflict");
    upsertFile("entries-conflict", token, "/file.txt", "abc".getBytes());

    mockMvc
        .perform(
            post("/fs/entries")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "path":"/file.txt",
                      "entryType":"FILE",
                      "sizeBytes":3,
                      "checksum":"%s",
                      "deleted":false
                    }
                    """
                        .formatted(sha256Hex("abc".getBytes()))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("FS_PATH_CONFLICT"));
  }

  @Test
  void patchEntries_renamesEntry() throws Exception {
    final String token = obtainToken("patch-rename");
    final String entryId = upsertFile("patch-rename", token, "/old.txt", "abc".getBytes());

    mockMvc
        .perform(
            patch("/fs/entries/{id}", entryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"newPath":"/renamed.txt"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("/renamed.txt"));
  }

  @Test
  void patchEntries_returnsConflictOnTargetPathInUse() throws Exception {
    final String token = obtainToken("patch-conflict");
    final String firstEntryId = upsertFile("patch-conflict", token, "/first.txt", "abc".getBytes());
    upsertFile("patch-conflict", token, "/second.txt", "abc".getBytes());

    mockMvc
        .perform(
            patch("/fs/entries/{id}", firstEntryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"newPath":"/second.txt"}
                    """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("FS_PATH_CONFLICT"));
  }

  @Test
  void patchEntries_returnsNotFoundForUnknownEntry() throws Exception {
    final String token = obtainToken("patch-missing");

    mockMvc
        .perform(
            patch("/fs/entries/{id}", UUID.randomUUID().toString())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"newPath":"/whatever.txt"}
                    """))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("FS_ENTRY_NOT_FOUND"));
  }

  @Test
  void deleteEntries_marksAsDeleted() throws Exception {
    final String token = obtainToken("delete-entry");
    final String entryId = upsertFile("delete-entry", token, "/todelete.txt", "abc".getBytes());

    mockMvc
        .perform(delete("/fs/entries/{id}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(true));
  }

  @Test
  void deleteEntries_returnsNotFoundForUnknownEntry() throws Exception {
    final String token = obtainToken("delete-missing");

    mockMvc
        .perform(
            delete("/fs/entries/{id}", UUID.randomUUID().toString())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("FS_ENTRY_NOT_FOUND"));
  }

  @Test
  void getTree_returnsUnauthorizedWithoutToken() throws Exception {
    mockMvc.perform(get("/fs/tree")).andExpect(status().isUnauthorized());
  }

  // ---------- Helpers ----------

  private String upsertFile(
      final String username, final String token, final String path, final byte[] content)
      throws Exception {
    final String checksum = sha256Hex(content);
    final MvcResult result =
        mockMvc
            .perform(
                post("/fs/entries")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "path":"%s",
                          "entryType":"FILE",
                          "sizeBytes":%d,
                          "checksum":"%s",
                          "deleted":false
                        }
                        """
                            .formatted(path, content.length, checksum)))
            .andExpect(status().isOk())
            .andReturn();
    final JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
    final String entryId = node.get("entryId").asText();
    // Simular fin del pipeline de distribución (lo hace
    // FileContentDistributionService.distributeUploadStreaming). Sin esto el entry queda
    // contentUploaded=false y no aparece en /fs/tree.
    fsEntryPort
        .findByUsernameAndEntryId(username, entryId)
        .map(entry -> entry.withContentUploaded(true))
        .ifPresent(fsEntryPort::save);
    return entryId;
  }

  private static String sha256Hex(final byte[] content) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
