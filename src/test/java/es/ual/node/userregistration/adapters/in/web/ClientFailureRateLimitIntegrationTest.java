package es.ual.node.userregistration.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** Integration test for optional client failure rate limiter. */
@SpringBootTest(
    properties = {
      "node.features.discovery-enabled=false",
      "node.features.negotiation-enabled=false",
      "node.features.custody-enabled=false",
      "node.features.recovery-enabled=false",
      "node.client-failure-rate-limit.enabled=true",
      "node.client-failure-rate-limit.max-failures=2",
      "node.client-failure-rate-limit.window-seconds=60",
      "node.client-failure-rate-limit.block-seconds=5"
    })
@AutoConfigureMockMvc
class ClientFailureRateLimitIntegrationTest {

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
  void repeatedClientFailuresAreTemporarilyRateLimited() throws Exception {
    final String invitationCode = userRegistrationService.issueRegistrationCode(500);

    mockMvc
        .perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "invitationCode":"%s",
                      "username":"limit-user",
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
                          "username":"limit-user",
                          "password":"Passw0rd!"
                        }
                        """))
            .andExpect(status().isOk())
            .andReturn();

    final JsonNode loginJson =
        objectMapper.readTree(loginResult.getResponse().getContentAsString());
    final String token = loginJson.get("token").asText();

    mockMvc
        .perform(
            get("/sync/changes").header("Authorization", "Bearer " + token).param("since", "-1"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            get("/sync/changes").header("Authorization", "Bearer " + token).param("since", "-1"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            get("/sync/changes").header("Authorization", "Bearer " + token).param("since", "-1"))
        .andExpect(status().isTooManyRequests());
  }
}
