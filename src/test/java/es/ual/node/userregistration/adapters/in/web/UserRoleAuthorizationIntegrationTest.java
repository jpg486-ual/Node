package es.ual.node.userregistration.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.bootstrap.configuration.TestNodeIdentityKeys;
import es.ual.node.userregistration.application.UserRegistrationService;
import es.ual.node.userregistration.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Integration tests for base RBAC authorization on client endpoints. */
@SpringBootTest(
    properties = {
      "node.features.discovery-enabled=false",
      "node.features.negotiation-enabled=false",
      "node.features.custody-enabled=false",
      "node.features.recovery-enabled=false"
    })
@AutoConfigureMockMvc
class UserRoleAuthorizationIntegrationTest {

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
  void endUserCanAccessClientEndpointsWhileNodeAdminIsForbidden() throws Exception {
    final String endUserCode =
        userRegistrationService.issueRegistrationCode(500, UserRole.END_USER);
    register(endUserCode, "end-user-rbac");
    final String endUserToken = loginToken("end-user-rbac");

    mockMvc
        .perform(get("/fs/tree").header("Authorization", "Bearer " + endUserToken))
        .andExpect(status().isOk());

    final String adminCode =
        userRegistrationService.issueRegistrationCode(600, UserRole.NODE_ADMIN);
    register(adminCode, "node-admin-rbac");
    final String adminToken = loginToken("node-admin-rbac");

    mockMvc
        .perform(get("/fs/tree").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isForbidden());
  }

  private void register(final String invitationCode, final String username) throws Exception {
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
  }

  private String loginToken(final String username) throws Exception {
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
