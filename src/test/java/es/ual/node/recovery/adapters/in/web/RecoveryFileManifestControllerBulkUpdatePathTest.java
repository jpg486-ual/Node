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
 * Tests for {@code PATCH /recovery/file-manifests-bulk}. Pins HTTP code mappings of las excepciones
 * lanzadas por {@link TutorFileManifestCustodyService#updatePathBulk}.
 */
class RecoveryFileManifestControllerBulkUpdatePathTest {

  private static final String OWNER_NODE_ID = "node-owner";
  private static final String OTHER_NODE_ID = "node-stranger";
  private static final String VALID_BODY =
      "{\"entries\":["
          + "{\"fileId\":\"00000000-0000-0000-0000-000000000001\","
          + "\"directoryPath\":\"/jose/B\",\"originalFileName\":\"a.mov\"},"
          + "{\"fileId\":\"00000000-0000-0000-0000-000000000002\","
          + "\"directoryPath\":\"/jose/B\",\"originalFileName\":\"b.mov\"}]}";

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
  void bulkPatch_returns200WithUpdatedManifests() throws Exception {
    service.behavior = RecordingService.ServiceBehavior.SUCCESS;

    mockMvc
        .perform(
            patch("/recovery/file-manifests-bulk")
                .header(RequestSignatureValidator.HEADER_NODE_ID, OWNER_NODE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.manifests.length()").value(2));
  }

  @Test
  void bulkPatch_returns403WhenAnyOwnershipFails() throws Exception {
    service.behavior = RecordingService.ServiceBehavior.THROW_SECURITY;

    mockMvc
        .perform(
            patch("/recovery/file-manifests-bulk")
                .header(RequestSignatureValidator.HEADER_NODE_ID, OTHER_NODE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  void bulkPatch_returns404WhenAnyManifestMissing() throws Exception {
    service.behavior = RecordingService.ServiceBehavior.THROW_NOT_FOUND;

    mockMvc
        .perform(
            patch("/recovery/file-manifests-bulk")
                .header(RequestSignatureValidator.HEADER_NODE_ID, OWNER_NODE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isNotFound());
  }

  @Test
  void bulkPatch_returns400WhenPayloadEmpty() throws Exception {
    mockMvc
        .perform(
            patch("/recovery/file-manifests-bulk")
                .header(RequestSignatureValidator.HEADER_NODE_ID, OWNER_NODE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entries\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void bulkPatch_returns400WhenNodeIdHeaderAbsent() throws Exception {
    mockMvc
        .perform(
            patch("/recovery/file-manifests-bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
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
              java.time.Instant.parse("2026-05-03T12:00:00Z"), java.time.ZoneOffset.UTC));
    }

    private static es.ual.node.bootstrap.configuration.NodeTopologyProperties freshTopology() {
      final es.ual.node.bootstrap.configuration.NodeTopologyProperties topology =
          new es.ual.node.bootstrap.configuration.NodeTopologyProperties();
      topology.setTutorAcceptedPublicKeys(List.of("pubkey-test"));
      return topology;
    }

    @Override
    public List<CustodiedFileManifest> updatePathBulk(
        final String callerNodeId, final List<BulkUpdateEntry> entries) {
      return switch (behavior) {
        case SUCCESS ->
            entries.stream()
                .map(
                    e ->
                        new CustodiedFileManifest(
                            e.fileId(),
                            callerNodeId,
                            "pubkey-test",
                            e.directoryPath(),
                            e.originalFileName(),
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
                            java.time.Instant.parse("2026-05-03T12:00:00Z")))
                .toList();
        case THROW_SECURITY -> throw new SecurityException("caller does not own a manifest");
        case THROW_NOT_FOUND -> throw new NoSuchElementException("manifest not found");
      };
    }
  }
}
