package es.ual.node.userregistration.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.bootstrap.configuration.TestNodeIdentityKeys;
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

/** Integration tests for filesystem/file/sync error contract payloads. */
@SpringBootTest(
    properties = {
      "node.features.discovery-enabled=false",
      "node.features.negotiation-enabled=false",
      "node.features.custody-enabled=false",
      "node.features.recovery-enabled=false"
    })
@AutoConfigureMockMvc
class ClientApiErrorContractIntegrationTest {

  private static final String[] NODE_IDENTITY_PROPERTIES =
      TestNodeIdentityKeys.generatePropertyValues();

  @Autowired private MockMvc mockMvc;

  @Autowired private UserRegistrationService userRegistrationService;

  @Autowired private ObjectMapper objectMapper;

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
  void syncChangesWithNegativeCursorReturnsStableErrorCode() throws Exception {
    final String token = registerAndLogin("sync-errors-user");

    mockMvc
        .perform(
            get("/sync/changes").header("Authorization", "Bearer " + token).param("since", "-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("SYNC_INVALID_SINCE"))
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.timestamp").isString());
  }

  @Test
  void fsTreeWithNegativeCursorReturnsStableErrorCode() throws Exception {
    final String token = registerAndLogin("tree-errors-user");

    mockMvc
        .perform(
            get("/fs/tree").header("Authorization", "Bearer " + token).param("sinceCursor", "-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("FS_TREE_INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.timestamp").isString());
  }

  @Test
  void uploadSessionForMissingEntryReturnsStableErrorCode() throws Exception {
    final String token = registerAndLogin("upload-errors-user");

    mockMvc
        .perform(
            post("/files/upload-sessions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "entryId":"missing-entry"
                    }
                    """))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("FILE_UPLOAD_ENTRY_NOT_FOUND"))
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.timestamp").isString());
  }

  @Test
  void fileDownloadForMissingEntryReturnsStableErrorCode() throws Exception {
    final String token = registerAndLogin("files-errors-user");

    mockMvc
        .perform(
            get("/files/entries/{id}/content", "missing-entry")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("FILE_ENTRY_NOT_FOUND"))
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.timestamp").isString());
  }

  private String registerAndLogin(final String username) throws Exception {
    final String invitationCode = userRegistrationService.issueRegistrationCode(500);

    mockMvc
        .perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "invitationCode":"%s",
                      "username":"%s",
                      "password":"Passw0rd!"
                    }
                    """
                        .formatted(invitationCode, username)))
        .andExpect(status().isCreated());

    final MvcResult loginResult =
        mockMvc
            .perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "username":"%s",
                          "password":"Passw0rd!"
                        }
                        """
                            .formatted(username)))
            .andExpect(status().isOk())
            .andReturn();

    final JsonNode loginJson =
        objectMapper.readTree(loginResult.getResponse().getContentAsString());
    return loginJson.get("token").asText();
  }
}
