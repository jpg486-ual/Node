package es.ual.node.userregistration.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.bootstrap.configuration.TestNodeIdentityKeys;
import es.ual.node.userregistration.application.UserRegistrationService;
import es.ual.node.userregistration.ports.out.UserQuotaPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies that {@code GET /auth/me} surfaces the live {@code quotaUsedBytes} consumption tracked
 * by {@link UserQuotaPort}. The client uses this together with {@code quotaMb} to render a usage
 * percentage and warn before an upload exceeds the allowance.
 */
@SpringBootTest(
    properties = {
      "node.features.discovery-enabled=false",
      "node.features.negotiation-enabled=false",
      "node.features.custody-enabled=false",
      "node.features.recovery-enabled=false"
    })
@AutoConfigureMockMvc
class AuthMeQuotaIntegrationTest {

  private static final String[] NODE_IDENTITY_PROPERTIES =
      TestNodeIdentityKeys.generatePropertyValues();

  @Autowired private MockMvc mockMvc;

  @Autowired private UserRegistrationService userRegistrationService;

  @Autowired private UserQuotaPort userQuotaPort;

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
  void meReturnsQuotaUsedBytesAfterReservation() throws Exception {
    final String invitationCode = userRegistrationService.issueRegistrationCode(50);
    final String username = "quota-aware-user";
    final String password = "Passw0rd!";

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
                        .formatted(invitationCode, username, password)))
        .andExpect(status().isCreated());

    final String token = loginToken(username, password);

    // Baseline: a fresh user has no consumption.
    mockMvc
        .perform(get("/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value(username))
        .andExpect(jsonPath("$.quotaMb").value(50))
        .andExpect(jsonPath("$.quotaUsedBytes").value(0))
        .andExpect(jsonPath("$.role").value("END_USER"));

    // Simulate the upload pipeline charging the quota directly through the port.
    final long reserved = 1234567L;
    final boolean ok = userQuotaPort.tryReserve(username, reserved);
    assert ok;

    // /auth/me reflects the new usage live (no caching).
    mockMvc
        .perform(get("/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value(username))
        .andExpect(jsonPath("$.quotaUsedBytes").value(reserved));
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
