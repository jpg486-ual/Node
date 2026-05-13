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

/** Integration tests for auth error contract payloads. */
@SpringBootTest(
    properties = {
      "node.features.discovery-enabled=false",
      "node.features.negotiation-enabled=false",
      "node.features.custody-enabled=false",
      "node.features.recovery-enabled=false"
    })
@AutoConfigureMockMvc
class AuthErrorContractIntegrationTest {

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
  void loginWithInvalidPasswordReturnsStableErrorCode() throws Exception {
    final String invitationCode = userRegistrationService.issueRegistrationCode(500);

    mockMvc
        .perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "invitationCode":"%s",
                      "username":"error-user",
                      "password":"Passw0rd!"
                    }
                    """
                        .formatted(invitationCode)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "username":"error-user",
                      "password":"wrong-password"
                    }
                    """))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"))
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.timestamp").isString());
  }

  @Test
  void meWithoutAuthorizationReturnsInvalidSessionErrorCode() throws Exception {
    mockMvc
        .perform(get("/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("INVALID_SESSION"))
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.timestamp").isString());
  }

  @Test
  void refreshWithoutAuthorizationReturnsInvalidSessionErrorCode() throws Exception {
    mockMvc
        .perform(post("/auth/refresh"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("INVALID_SESSION"))
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.timestamp").isString());
  }

  @Test
  void refreshWithRevokedSessionReturnsInvalidSessionErrorCode() throws Exception {
    final String invitationCode = userRegistrationService.issueRegistrationCode(600);

    mockMvc
        .perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "invitationCode":"%s",
                      "username":"refresh-error-user",
                      "password":"Passw0rd!"
                    }
                    """
                        .formatted(invitationCode)))
        .andExpect(status().isCreated());

    final String token = loginToken("refresh-error-user", "Passw0rd!");

    mockMvc
        .perform(post("/auth/logout").header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(post("/auth/refresh").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("INVALID_SESSION"))
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.timestamp").isString());
  }

  private String loginToken(final String username, final String password) throws Exception {
    final String response =
        mockMvc
            .perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "username":"%s",
                          "password":"%s"
                        }
                        """
                            .formatted(username, password)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final JsonNode json = objectMapper.readTree(response);
    return json.get("token").asText();
  }
}
