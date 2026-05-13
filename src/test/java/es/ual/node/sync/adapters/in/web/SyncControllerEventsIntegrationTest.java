package es.ual.node.sync.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.ual.node.userregistration.adapters.in.web.ClientAuthIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Integration tests for {@code GET /sync/events}. */
class SyncControllerEventsIntegrationTest extends ClientAuthIntegrationTestBase {

  @Test
  void getEvents_returnsTextEventStreamWithOkStatus() throws Exception {
    final String token = obtainToken("events-status");

    mockMvc
        .perform(
            get("/sync/events")
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .andExpect(request().asyncStarted());
  }

  @Test
  void getEvents_writesConnectedEventToInitialResponseBody() throws Exception {
    final String token = obtainToken("events-connected");

    final MvcResult asyncResult =
        mockMvc
            .perform(
                get("/sync/events")
                    .header("Authorization", "Bearer " + token)
                    .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted())
            .andReturn();

    final String body = asyncResult.getResponse().getContentAsString();
    assertThat(body).contains("event:connected");
    assertThat(body).contains("\"type\":\"connected\"");
    assertThat(body).contains("\"cursor\":0");
  }

  @Test
  void getEvents_returnsUnauthorizedWithoutToken() throws Exception {
    mockMvc
        .perform(get("/sync/events").accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isUnauthorized());
  }
}
