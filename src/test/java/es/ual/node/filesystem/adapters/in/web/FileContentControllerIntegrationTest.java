package es.ual.node.filesystem.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import es.ual.node.userregistration.adapters.in.web.ClientAuthIntegrationTestBase;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MvcResult;

/** Integration tests for {@link FileContentController}. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FileContentControllerIntegrationTest extends ClientAuthIntegrationTestBase {

  @Test
  void putContent_storesAndAllowsDownloadWithChecksumHeader() throws Exception {
    final String token = obtainToken("content-roundtrip");
    final byte[] payload = "round-trip-content".getBytes();
    final String entryId = upsertFileMetadata(token, "/round.bin", payload);

    mockMvc
        .perform(
            put("/files/entries/{id}/content", entryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entryId").value(entryId))
        .andExpect(jsonPath("$.sizeBytes").value(payload.length))
        .andExpect(jsonPath("$.checksum").value(sha256Hex(payload)));

    mockMvc
        .perform(
            get("/files/entries/{id}/content", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(content().bytes(payload))
        .andExpect(header().string("X-Content-SHA256", sha256Hex(payload)));
  }

  @Test
  void putContent_returnsConflictOnSizeMismatch() throws Exception {
    final String token = obtainToken("content-size-mismatch");
    final byte[] declared = "declared".getBytes();
    final String entryId = upsertFileMetadata(token, "/size.bin", declared);

    mockMvc
        .perform(
            put("/files/entries/{id}/content", entryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content("shorter".getBytes()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("FILE_CONTENT_CONFLICT"));
  }

  @Test
  void putContent_returnsConflictOnChecksumMismatch() throws Exception {
    final String token = obtainToken("content-checksum-mismatch");
    final byte[] declared = "declaredXX".getBytes();
    final String entryId = upsertFileMetadata(token, "/checksum.bin", declared);
    final byte[] differentSameSize = new byte[declared.length];
    for (int i = 0; i < differentSameSize.length; i++) {
      differentSameSize[i] = (byte) 'z';
    }

    mockMvc
        .perform(
            put("/files/entries/{id}/content", entryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(differentSameSize))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("FILE_CONTENT_CONFLICT"));
  }

  @Test
  void putContent_returnsNotFoundForUnknownEntry() throws Exception {
    final String token = obtainToken("content-unknown-put");

    mockMvc
        .perform(
            put("/files/entries/{id}/content", UUID.randomUUID().toString())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content("any".getBytes()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("FILE_ENTRY_NOT_FOUND"));
  }

  @Test
  void getContent_returnsNotFoundForUnknownEntry() throws Exception {
    final String token = obtainToken("content-unknown-get");

    mockMvc
        .perform(
            get("/files/entries/{id}/content", UUID.randomUUID().toString())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("FILE_ENTRY_NOT_FOUND"));
  }

  @Test
  void getContent_returnsUnauthorizedWithoutToken() throws Exception {
    mockMvc
        .perform(get("/files/entries/{id}/content", UUID.randomUUID().toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void createUploadSession_returnsOpenSessionForActiveEntry() throws Exception {
    final String token = obtainToken("session-create");
    final byte[] payload = "session-content".getBytes();
    final String entryId = upsertFileMetadata(token, "/session.bin", payload);

    mockMvc
        .perform(
            post("/files/upload-sessions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entryId\":\"%s\"}".formatted(entryId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entryId").value(entryId))
        .andExpect(jsonPath("$.uploadedBytes").value(0))
        .andExpect(jsonPath("$.expectedSizeBytes").value(payload.length))
        .andExpect(jsonPath("$.status").value("OPEN"));
  }

  @Test
  void createUploadSession_returnsNotFoundForUnknownEntry() throws Exception {
    final String token = obtainToken("session-unknown");

    mockMvc
        .perform(
            post("/files/upload-sessions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entryId\":\"%s\"}".formatted(UUID.randomUUID().toString())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("FILE_UPLOAD_ENTRY_NOT_FOUND"));
  }

  @Test
  void appendChunks_progressesUploadedBytes() throws Exception {
    final String token = obtainToken("session-append");
    final byte[] payload = "0123456789".getBytes();
    final String entryId = upsertFileMetadata(token, "/append.bin", payload);
    final String sessionId = createUploadSession(token, entryId);

    final byte[] firstChunk = new byte[] {payload[0], payload[1], payload[2]};
    final byte[] secondChunk = new byte[] {payload[3], payload[4]};

    mockMvc
        .perform(
            put("/files/upload-sessions/{id}/chunks", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .param("offset", "0")
                .content(firstChunk))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uploadedBytes").value(3));

    mockMvc
        .perform(
            put("/files/upload-sessions/{id}/chunks", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .param("offset", "3")
                .content(secondChunk))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uploadedBytes").value(5));
  }

  @Test
  void appendChunks_returnsConflictOnOffsetMismatch() throws Exception {
    final String token = obtainToken("session-offset-mismatch");
    final byte[] payload = "abcdefghij".getBytes();
    final String entryId = upsertFileMetadata(token, "/offset.bin", payload);
    final String sessionId = createUploadSession(token, entryId);

    mockMvc
        .perform(
            put("/files/upload-sessions/{id}/chunks", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .param("offset", "5")
                .content("xyz".getBytes()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("FILE_UPLOAD_CHUNK_CONFLICT"));
  }

  @Test
  void completeUpload_promotesContentWhenAllChunksDelivered() throws Exception {
    final String token = obtainToken("session-complete");
    final byte[] payload = "complete-flow".getBytes();
    final String entryId = upsertFileMetadata(token, "/complete.bin", payload);
    final String sessionId = createUploadSession(token, entryId);

    mockMvc
        .perform(
            put("/files/upload-sessions/{id}/chunks", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .param("offset", "0")
                .content(payload))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/files/upload-sessions/{id}/complete", sessionId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entryId").value(entryId))
        .andExpect(jsonPath("$.sizeBytes").value(payload.length))
        .andExpect(jsonPath("$.checksum").value(sha256Hex(payload)));

    mockMvc
        .perform(
            get("/files/entries/{id}/content", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(content().bytes(payload));
  }

  @Test
  void completeUpload_returnsConflictWhenIncomplete() throws Exception {
    final String token = obtainToken("session-incomplete");
    final byte[] payload = "longer-content".getBytes();
    final String entryId = upsertFileMetadata(token, "/incomplete.bin", payload);
    final String sessionId = createUploadSession(token, entryId);

    mockMvc
        .perform(
            put("/files/upload-sessions/{id}/chunks", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .param("offset", "0")
                .content(new byte[] {payload[0], payload[1]}))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/files/upload-sessions/{id}/complete", sessionId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("FILE_UPLOAD_COMPLETE_CONFLICT"));
  }

  @Test
  void getUploadSession_returnsCurrentState() throws Exception {
    final String token = obtainToken("session-get");
    final byte[] payload = "abcdef".getBytes();
    final String entryId = upsertFileMetadata(token, "/state.bin", payload);
    final String sessionId = createUploadSession(token, entryId);

    mockMvc
        .perform(
            get("/files/upload-sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionId").value(sessionId))
        .andExpect(jsonPath("$.entryId").value(entryId))
        .andExpect(jsonPath("$.status").value("OPEN"));
  }

  @Test
  void getUploadSession_returnsNotFoundForUnknownSession() throws Exception {
    final String token = obtainToken("session-unknown-get");

    mockMvc
        .perform(
            get("/files/upload-sessions/{id}", UUID.randomUUID().toString())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("FILE_UPLOAD_SESSION_NOT_FOUND"));
  }

  // ---------- Helpers ----------

  private String upsertFileMetadata(final String token, final String path, final byte[] content)
      throws Exception {
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
                            .formatted(path, content.length, sha256Hex(content))))
            .andExpect(status().isOk())
            .andReturn();
    final JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
    return node.get("entryId").asText();
  }

  private String createUploadSession(final String token, final String entryId) throws Exception {
    final MvcResult result =
        mockMvc
            .perform(
                post("/files/upload-sessions")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"entryId\":\"%s\"}".formatted(entryId)))
            .andExpect(status().isOk())
            .andReturn();
    final JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
    return node.get("sessionId").asText();
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
