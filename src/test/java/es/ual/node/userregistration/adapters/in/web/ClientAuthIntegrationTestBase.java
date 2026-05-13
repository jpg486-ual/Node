package es.ual.node.userregistration.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.bootstrap.configuration.TestNodeIdentityKeys;
import es.ual.node.userregistration.application.UserRegistrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Base for client-facing integration tests requiring authenticated bearer tokens. */
@SpringBootTest(
    properties = {
      "node.features.discovery-enabled=false",
      "node.features.negotiation-enabled=false",
      "node.features.custody-enabled=false",
      "node.features.recovery-enabled=false"
    })
@AutoConfigureMockMvc
public abstract class ClientAuthIntegrationTestBase {

  protected static final String CLIENT_PASSWORD = "Passw0rd!";

  private static final String[] NODE_IDENTITY_PROPERTIES =
      TestNodeIdentityKeys.generatePropertyValues();

  @Autowired protected MockMvc mockMvc;

  @Autowired protected UserRegistrationService userRegistrationService;

  @Autowired protected ObjectMapper objectMapper;

  @DynamicPropertySource
  static void configureNodeIdentity(final DynamicPropertyRegistry registry) {
    for (String property : NODE_IDENTITY_PROPERTIES) {
      final int separatorIndex = property.indexOf('=');
      final String key = property.substring(0, separatorIndex);
      final String value = property.substring(separatorIndex + 1);
      registry.add(key, () -> value);
    }
  }

  /**
   * Registers a user with the given username and logs in to obtain a bearer token.
   *
   * @param username username to register
   * @return bearer token
   * @throws Exception when MockMvc fails
   */
  protected String obtainToken(final String username) throws Exception {
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
                      "password":"%s"
                    }
                    """
                        .formatted(invitationCode, username, CLIENT_PASSWORD)))
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
                          "password":"%s"
                        }
                        """
                            .formatted(username, CLIENT_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();

    final JsonNode loginJson =
        objectMapper.readTree(loginResult.getResponse().getContentAsString());
    return loginJson.get("token").asText();
  }
}
