package es.ual.node.recovery.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.recovery.application.TutorFileManifestCustodyService;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for the {@code DELETE /recovery/file-manifests/&#123;fileId&#125;} endpoint.
 * Standalone MockMvc — pins HTTP code mappings of the exceptions thrown by {@link
 * TutorFileManifestCustodyService#delete}.
 */
class RecoveryFileManifestControllerDeleteTest {

  private static final String FILE_ID = "00000000-0000-0000-0000-000000000001";
  private static final String OWNER_NODE_ID = "node-owner";
  private static final String OTHER_NODE_ID = "node-stranger";

  private RecordingService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = new RecordingService();
    final RecoveryFileManifestController controller = new RecoveryFileManifestController(service);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void deleteReturns204WhenCallerOwnsManifest() throws Exception {
    service.behavior = RecordingService.ServiceBehavior.SUCCESS;

    mockMvc
        .perform(
            delete("/recovery/file-manifests/{fileId}", FILE_ID)
                .header(RequestSignatureValidator.HEADER_NODE_ID, OWNER_NODE_ID))
        .andExpect(status().isNoContent());
  }

  @Test
  void deleteReturns403WhenCallerDoesNotOwnManifest() throws Exception {
    service.behavior = RecordingService.ServiceBehavior.THROW_SECURITY;

    mockMvc
        .perform(
            delete("/recovery/file-manifests/{fileId}", FILE_ID)
                .header(RequestSignatureValidator.HEADER_NODE_ID, OTHER_NODE_ID))
        .andExpect(status().isForbidden());
  }

  @Test
  void deleteReturns404WhenManifestNotFound() throws Exception {
    service.behavior = RecordingService.ServiceBehavior.THROW_NOT_FOUND;

    mockMvc
        .perform(
            delete("/recovery/file-manifests/{fileId}", FILE_ID)
                .header(RequestSignatureValidator.HEADER_NODE_ID, OWNER_NODE_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteReturns400WhenNodeIdHeaderAbsent() throws Exception {
    mockMvc
        .perform(delete("/recovery/file-manifests/{fileId}", FILE_ID))
        .andExpect(status().isBadRequest());
  }

  /** Test double for {@link TutorFileManifestCustodyService} — switches behavior per case. */
  private static final class RecordingService extends TutorFileManifestCustodyService {
    enum ServiceBehavior {
      SUCCESS,
      THROW_SECURITY,
      THROW_NOT_FOUND
    }

    ServiceBehavior behavior = ServiceBehavior.SUCCESS;

    RecordingService() {
      super(
          freshTopology(),
          new es.ual.node.recovery.adapters.out.memory.InMemoryCustodiedFileManifestPort(),
          java.time.Clock.fixed(
              java.time.Instant.parse("2026-05-02T12:00:00Z"), java.time.ZoneOffset.UTC));
    }

    private static es.ual.node.bootstrap.configuration.NodeTopologyProperties freshTopology() {
      final es.ual.node.bootstrap.configuration.NodeTopologyProperties topology =
          new es.ual.node.bootstrap.configuration.NodeTopologyProperties();
      topology.setTutorAcceptedPublicKeys(List.of("pubkey-test"));
      return topology;
    }

    @Override
    public boolean delete(final String fileId, final String callerNodeId) {
      return switch (behavior) {
        case SUCCESS -> true;
        case THROW_SECURITY -> throw new SecurityException("caller does not own manifest");
        case THROW_NOT_FOUND -> throw new NoSuchElementException("manifest not found");
      };
    }
  }
}
