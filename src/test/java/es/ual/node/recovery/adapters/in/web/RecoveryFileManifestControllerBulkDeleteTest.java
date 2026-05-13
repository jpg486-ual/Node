package es.ual.node.recovery.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.recovery.application.TutorFileManifestCustodyService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Tests for {@code DELETE /recovery/file-manifests-bulk}. Pins HTTP code mappings of las
 * excepciones lanzadas por {@link TutorFileManifestCustodyService#deleteBulk}.
 */
class RecoveryFileManifestControllerBulkDeleteTest {

  private static final String OWNER_NODE_ID = "node-owner";
  private static final String OTHER_NODE_ID = "node-stranger";
  private static final String VALID_BODY =
      "{\"fileIds\":[\"00000000-0000-0000-0000-000000000001\","
          + "\"00000000-0000-0000-0000-000000000002\"]}";

  private RecordingService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = new RecordingService();
    final RecoveryFileManifestBulkController controller =
        new RecoveryFileManifestBulkController(service);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void bulkDelete_returns200WithCounts() throws Exception {
    service.behavior = RecordingService.ServiceBehavior.SUCCESS;

    mockMvc
        .perform(
            delete("/recovery/file-manifests-bulk")
                .header(RequestSignatureValidator.HEADER_NODE_ID, OWNER_NODE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(2))
        .andExpect(jsonPath("$.missingCount").value(0));
  }

  @Test
  void bulkDelete_returns403WhenAnyOwnershipFails() throws Exception {
    service.behavior = RecordingService.ServiceBehavior.THROW_SECURITY;

    mockMvc
        .perform(
            delete("/recovery/file-manifests-bulk")
                .header(RequestSignatureValidator.HEADER_NODE_ID, OTHER_NODE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  void bulkDelete_returns400WhenPayloadEmpty() throws Exception {
    mockMvc
        .perform(
            delete("/recovery/file-manifests-bulk")
                .header(RequestSignatureValidator.HEADER_NODE_ID, OWNER_NODE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileIds\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void bulkDelete_returns400WhenNodeIdHeaderAbsent() throws Exception {
    mockMvc
        .perform(
            delete("/recovery/file-manifests-bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isBadRequest());
  }

  /** Test double for {@link TutorFileManifestCustodyService} — switches behavior per case. */
  private static final class RecordingService extends TutorFileManifestCustodyService {
    enum ServiceBehavior {
      SUCCESS,
      THROW_SECURITY
    }

    ServiceBehavior behavior = ServiceBehavior.SUCCESS;

    RecordingService() {
      super(
          freshTopology(),
          new es.ual.node.recovery.adapters.out.memory.InMemoryCustodiedFileManifestPort(),
          java.time.Clock.fixed(
              java.time.Instant.parse("2026-05-03T12:00:00Z"), java.time.ZoneOffset.UTC));
    }

    private static es.ual.node.bootstrap.configuration.NodeTopologyProperties freshTopology() {
      final es.ual.node.bootstrap.configuration.NodeTopologyProperties topology =
          new es.ual.node.bootstrap.configuration.NodeTopologyProperties();
      topology.setTutorAcceptedPublicKeys(List.of("pubkey-test"));
      return topology;
    }

    @Override
    public DeleteBulkResult deleteBulk(final String callerNodeId, final List<String> fileIds) {
      return switch (behavior) {
        case SUCCESS -> new DeleteBulkResult(fileIds.size(), 0);
        case THROW_SECURITY -> throw new SecurityException("caller does not own a manifest");
      };
    }
  }
}
