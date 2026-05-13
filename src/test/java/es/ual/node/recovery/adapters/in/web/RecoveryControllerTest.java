package es.ual.node.recovery.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ual.node.recovery.application.TutorRecoveryService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit tests for {@link RecoveryController}. */
@ExtendWith(MockitoExtension.class)
class RecoveryControllerTest {

  @Mock private TutorRecoveryService tutorRecoveryService;

  private RecoveryController controller;

  @BeforeEach
  void setUp() {
    controller = new RecoveryController(tutorRecoveryService);
  }

  @Test
  void reconstructReturnsBinaryPayloadWhenReconstructionSucceeds() {
    final byte[] payload = "recovered-runtime-payload".getBytes(StandardCharsets.UTF_8);
    final TutorRecoveryService.ReconstructedPayload reconstructed =
        new TutorRecoveryService.ReconstructedPayload(
            "file-rs-1",
            "SHA-256",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            payload);

    when(tutorRecoveryService.reconstruct(
            any(TutorRecoveryService.ReconstructRecoveryFragmentsRequest.class)))
        .thenReturn(reconstructed);

    final RecoveryReconstructPayload requestPayload = validReconstructPayload();
    final ResponseEntity<byte[]> response = controller.reconstruct(requestPayload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getFirst("X-File-Id")).isEqualTo("file-rs-1");
    assertThat(response.getHeaders().getFirst("X-Checksum-Algorithm")).isEqualTo("SHA-256");
    assertThat(response.getHeaders().getFirst("X-Checksum"))
        .isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    assertThat(response.getHeaders().getFirst("X-Size-Bytes"))
        .isEqualTo(String.valueOf(payload.length));
    assertThat(response.getBody()).isEqualTo(payload);

    verify(tutorRecoveryService)
        .reconstruct(any(TutorRecoveryService.ReconstructRecoveryFragmentsRequest.class));
  }

  @Test
  void reconstructReturnsNotFoundWhenAFragmentIsMissing() {
    when(tutorRecoveryService.reconstruct(
            any(TutorRecoveryService.ReconstructRecoveryFragmentsRequest.class)))
        .thenThrow(new NoSuchElementException("fragment not found"));

    final ResponseEntity<byte[]> response = controller.reconstruct(validReconstructPayload());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNull();
  }

  @Test
  void reconstructReturnsBadRequestWhenRequestIsInvalid() {
    when(tutorRecoveryService.reconstruct(
            any(TutorRecoveryService.ReconstructRecoveryFragmentsRequest.class)))
        .thenThrow(new IllegalArgumentException("at least k fragment references are required"));

    final ResponseEntity<byte[]> response = controller.reconstruct(validReconstructPayload());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNull();
  }

  private RecoveryReconstructPayload validReconstructPayload() {
    final RecoveryReconstructPayload payload = new RecoveryReconstructPayload();
    payload.setFileId("file-rs-1");
    payload.setExpectedOriginalHash(
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    payload.setRedundancyN(6);
    payload.setRedundancyK(4);
    payload.setSymbolSize(8);

    final RecoveryReconstructPayload.FragmentReferencePayload first =
        new RecoveryReconstructPayload.FragmentReferencePayload();
    first.setFragmentId("fragment-1");
    first.setIndex(0);
    first.setParity(false);

    final RecoveryReconstructPayload.FragmentReferencePayload second =
        new RecoveryReconstructPayload.FragmentReferencePayload();
    second.setFragmentId("fragment-2");
    second.setIndex(1);
    second.setParity(false);

    final RecoveryReconstructPayload.FragmentReferencePayload third =
        new RecoveryReconstructPayload.FragmentReferencePayload();
    third.setFragmentId("fragment-5");
    third.setIndex(4);
    third.setParity(true);

    final RecoveryReconstructPayload.FragmentReferencePayload fourth =
        new RecoveryReconstructPayload.FragmentReferencePayload();
    fourth.setFragmentId("fragment-6");
    fourth.setIndex(5);
    fourth.setParity(true);

    payload.setFragments(List.of(first, second, third, fourth));
    return payload;
  }
}
