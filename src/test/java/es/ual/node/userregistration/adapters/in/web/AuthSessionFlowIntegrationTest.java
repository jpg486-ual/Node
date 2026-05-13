package es.ual.node.userregistration.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.bootstrap.configuration.TestNodeIdentityKeys;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.userregistration.application.UserRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Integration test for client auth session flow and protected filesystem endpoint. */
@SpringBootTest(
    properties = {
      "node.features.discovery-enabled=false",
      "node.features.negotiation-enabled=false",
      "node.features.custody-enabled=false",
      "node.features.recovery-enabled=false",
      "node.client-files.base-directory=target/test-client-files",
      "node.client-files.staging-directory=target/test-client-files-staging"
    })
@AutoConfigureMockMvc
class AuthSessionFlowIntegrationTest {

  private static final String[] NODE_IDENTITY_PROPERTIES =
      TestNodeIdentityKeys.generatePropertyValues();

  @Autowired private MockMvc mockMvc;

  @Autowired private UserRegistrationService userRegistrationService;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private FsEntryPort fsEntryPort;

  /**
   * Marca el entry en {@code path} como {@code contentUploaded=true} para simular el fin del
   * pipeline de distribución (production lo hace en {@code
   * FileContentDistributionService.distributeUploadStreaming}). Sin esto los entries recién creados
   * vía {@code POST /fs/entries} quedan ocultos en {@code /fs/tree}.
   */
  private void markEntryUploaded(final String username, final String path) {
    fsEntryPort
        .findByUsernameAndPath(username, path)
        .map(e -> e.withContentUploaded(true))
        .ifPresent(fsEntryPort::save);
  }

  @DynamicPropertySource
  static void configureNodeIdentity(final DynamicPropertyRegistry registry) {
    for (String property : NODE_IDENTITY_PROPERTIES) {
      final int separatorIndex = property.indexOf('=');
      final String key = property.substring(0, separatorIndex);
      final String value = property.substring(separatorIndex + 1);
      registry.add(key, () -> value);
    }
  }

  @Test
  void registerLoginAccessFsThenLogoutRevokesSession() throws Exception {
    final String invitationCode = userRegistrationService.issueRegistrationCode(1500);

    mockMvc
        .perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "invitationCode":"%s",
                      "username":"eve",
                      "password":"Passw0rd!"
                    }
                    """
                        .formatted(invitationCode)))
        .andExpect(status().isCreated());

    final MvcResult loginResult =
        mockMvc
            .perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "username":"eve",
                          "password":"Passw0rd!"
                        }
                        """))
            .andExpect(status().isOk())
            .andReturn();

    final JsonNode loginJson =
        objectMapper.readTree(loginResult.getResponse().getContentAsString());
    final String token = loginJson.get("token").asText();

    mockMvc.perform(get("/fs/tree")).andExpect(status().isUnauthorized());

    mockMvc
        .perform(get("/fs/tree").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/fs/entries")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "path":"/docs/plan.txt",
                      "entryType":"FILE",
                      "sizeBytes":321,
                      "checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "deleted":false
                    }
                    """))
        .andExpect(status().isOk());
    markEntryUploaded("eve", "/docs/plan.txt");

    mockMvc
        .perform(
            post("/fs/entries")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                            "path":"/docs/plan.txt",
                            "entryType":"FILE",
                            "sizeBytes":999,
                            "checksum":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                            "deleted":false
                    }
                    """))
        .andExpect(status().isConflict());

    mockMvc
        .perform(
            post("/fs/entries")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                            "path":"/docs/content.txt",
                            "entryType":"FILE",
                            "sizeBytes":5,
                            "checksum":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                            "deleted":false
                    }
                    """))
        .andExpect(status().isOk());
    markEntryUploaded("eve", "/docs/content.txt");

    mockMvc
        .perform(
            post("/fs/entries")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                            "path":"/docs/resume.bin",
                            "entryType":"FILE",
                            "sizeBytes":11,
                            "checksum":"b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
                            "deleted":false
                    }
                    """))
        .andExpect(status().isOk());
    markEntryUploaded("eve", "/docs/resume.bin");

    mockMvc
        .perform(
            post("/fs/entries")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                            "path":"/docs/occupied.txt",
                            "entryType":"FILE",
                            "sizeBytes":100,
                            "checksum":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                            "deleted":false
                    }
                    """))
        .andExpect(status().isOk());
    markEntryUploaded("eve", "/docs/occupied.txt");

    final MvcResult treeResult =
        mockMvc
            .perform(get("/fs/tree").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();

    final JsonNode treeJson = objectMapper.readTree(treeResult.getResponse().getContentAsString());
    final JsonNode entries = treeJson.get("entries");
    boolean found = false;
    for (JsonNode entry : entries) {
      if ("/docs/plan.txt".equals(entry.get("path").asText())) {
        found = true;
        break;
      }
    }
    if (!found) {
      throw new AssertionError("Expected /docs/plan.txt entry in /fs/tree response");
    }

    String entryId = null;
    boolean occupiedFound = false;
    String contentEntryId = null;
    String resumableEntryId = null;
    for (JsonNode entry : entries) {
      if ("/docs/plan.txt".equals(entry.get("path").asText())) {
        entryId = entry.get("entryId").asText();
      }
      if ("/docs/occupied.txt".equals(entry.get("path").asText())) {
        occupiedFound = true;
      }
      if ("/docs/content.txt".equals(entry.get("path").asText())) {
        contentEntryId = entry.get("entryId").asText();
      }
      if ("/docs/resume.bin".equals(entry.get("path").asText())) {
        resumableEntryId = entry.get("entryId").asText();
      }
    }
    if (entryId == null) {
      throw new AssertionError("Expected entryId for /docs/plan.txt");
    }
    if (!occupiedFound) {
      throw new AssertionError("Expected /docs/occupied.txt entry in /fs/tree response");
    }
    if (contentEntryId == null) {
      throw new AssertionError("Expected entryId for /docs/content.txt");
    }
    if (resumableEntryId == null) {
      throw new AssertionError("Expected entryId for /docs/resume.bin");
    }

    mockMvc
        .perform(
            put("/files/entries/{id}/content", contentEntryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content("hello"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            get("/files/entries/{id}/content", contentEntryId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(content().bytes("hello".getBytes()));

    final MvcResult createUploadSessionResult =
        mockMvc
            .perform(
                post("/files/upload-sessions")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                                "entryId":"%s"
                        }
                        """
                            .formatted(resumableEntryId)))
            .andExpect(status().isOk())
            .andReturn();

    final JsonNode createUploadSessionJson =
        objectMapper.readTree(createUploadSessionResult.getResponse().getContentAsString());
    final String uploadSessionId = createUploadSessionJson.get("sessionId").asText();

    mockMvc
        .perform(
            get("/files/upload-sessions/{id}", uploadSessionId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            put("/files/upload-sessions/{id}/chunks", uploadSessionId)
                .header("Authorization", "Bearer " + token)
                .param("offset", "0")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content("hello "))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            put("/files/upload-sessions/{id}/chunks", uploadSessionId)
                .header("Authorization", "Bearer " + token)
                .param("offset", "6")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content("world"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/files/upload-sessions/{id}/complete", uploadSessionId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            get("/files/entries/{id}/content", resumableEntryId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(content().bytes("hello world".getBytes()));

    mockMvc
        .perform(
            get("/sync/changes").header("Authorization", "Bearer " + token).param("since", "0"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            get("/sync/events")
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            patch("/fs/entries/{id}", entryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "newPath":"/docs/occupied.txt"
                    }
                    """))
        .andExpect(status().isConflict());

    mockMvc
        .perform(
            patch("/fs/entries/{id}", entryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "newPath":"/archive/plan.txt"
                    }
                    """))
        .andExpect(status().isOk());

    mockMvc
        .perform(delete("/fs/entries/{id}", entryId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());

    final MvcResult refreshResult =
        mockMvc
            .perform(post("/auth/refresh").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();

    final JsonNode refreshJson =
        objectMapper.readTree(refreshResult.getResponse().getContentAsString());
    final String refreshedToken = refreshJson.get("token").asText();

    mockMvc
        .perform(get("/fs/tree").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());

    mockMvc
        .perform(get("/fs/tree").header("Authorization", "Bearer " + refreshedToken))
        .andExpect(status().isOk());

    mockMvc
        .perform(post("/auth/logout").header("Authorization", "Bearer " + refreshedToken))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/fs/tree").header("Authorization", "Bearer " + refreshedToken))
        .andExpect(status().isUnauthorized());
  }
}
