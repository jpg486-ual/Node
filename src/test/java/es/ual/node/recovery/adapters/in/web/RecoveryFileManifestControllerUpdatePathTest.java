package es.ual.node.recovery.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.recovery.application.TutorFileManifestCustodyService;
import es.ual.node.recovery.domain.CustodiedFileManifest;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Tests for {@code PATCH /recovery/file-manifests/&#123;fileId&#125;}. Pins HTTP code mappings of
 * las excepciones lanzadas por {@link TutorFileManifestCustodyService#updatePath}.
 */
class RecoveryFileManifestControllerUpdatePathTest {

  private static final String FILE_ID = "00000000-0000-0000-0000-000000000001";
  private static final String OWNER_NODE_ID = "node-owner";
  private static final String OTHER_NODE_ID = "node-stranger";
  private static final String VALID_BODY =
      "{\"directoryPath\":\"/jose/B\",\"originalFileName\":\"renamed.mov\"}";

  private RecordingService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = new RecordingService();
    final RecoveryFileManifestController controller = new RecoveryFileManifestController(service);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void patchReturns200WithUpdatedManifest() throws Exception {
    service.behavior = RecordingService.ServiceBehavior.SUCCESS;

    mockMvc
        .perform(
            patch("/recovery/file-manifests/{fileId}", FILE_ID)
                .header(RequestSignatureValidator.HEADER_NODE_ID, OWNER_NODE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.directoryPath").value("/jose/B"))
        .andExpect(jsonPath("$.originalFileName").value("renamed.mov"));
  }

  @Test
  void patchReturns403WhenCallerDoesNotOwnManifest() throws Exception {
    service.behavior = RecordingService.ServiceBehavior.THROW_SECURITY;

    mockMvc
        .perform(
            patch("/recovery/file-manifests/{fileId}", FILE_ID)
                .header(RequestSignatureValidator.HEADER_NODE_ID, OTHER_NODE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  void patchReturns404WhenManifestNotFound() throws Exception {
    service.behavior = RecordingService.ServiceBehavior.THROW_NOT_FOUND;

    mockMvc
        .perform(
            patch("/recovery/file-manifests/{fileId}", FILE_ID)
                .header(RequestSignatureValidator.HEADER_NODE_ID, OWNER_NODE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isNotFound());
  }

  @Test
  void patchReturns400WhenInvalidBody() throws Exception {
    service.behavior = RecordingService.ServiceBehavior.THROW_INVALID;

    mockMvc
        .perform(
            patch("/recovery/file-manifests/{fileId}", FILE_ID)
                .header(RequestSignatureValidator.HEADER_NODE_ID, OWNER_NODE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"directoryPath\":\"no-leading-slash\",\"originalFileName\":\"x.txt\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void patchReturns400WhenNodeIdHeaderAbsent() throws Exception {
    mockMvc
        .perform(
            patch("/recovery/file-manifests/{fileId}", FILE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isBadRequest());
  }

  /** Test double for {@link TutorFileManifestCustodyService} — switches behavior per case. */
  private static final class RecordingService extends TutorFileManifestCustodyService {
    enum ServiceBehavior {
      SUCCESS,
      THROW_SECURITY,
      THROW_NOT_FOUND,
      THROW_INVALID
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
    public CustodiedFileManifest updatePath(
        final String fileId,
        final String callerNodeId,
        final String directoryPath,
        final String originalFileName) {
      return switch (behavior) {
        case SUCCESS ->
            new CustodiedFileManifest(
                fileId,
                callerNodeId,
                "pubkey-test",
                directoryPath,
                originalFileName,
                "a".repeat(64),
                4096L,
                null,
                null,
                3,
                1024L,
                3,
                2,
                List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)),
                null,
                null,
                java.time.Instant.parse("2026-05-03T12:00:00Z"));
        case THROW_SECURITY -> throw new SecurityException("caller does not own manifest");
        case THROW_NOT_FOUND -> throw new NoSuchElementException("manifest not found");
        case THROW_INVALID -> throw new IllegalArgumentException("invalid path");
      };
    }
  }
}
