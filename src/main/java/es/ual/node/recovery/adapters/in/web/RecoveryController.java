package es.ual.node.recovery.adapters.in.web;

import es.ual.node.recovery.application.TutorRecoveryService;
import es.ual.node.recovery.domain.RecoveryOrphanFragment;
import java.util.Base64;
import java.util.NoSuchElementException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for tutor recovery custody operations. */
@RestController
@ConditionalOnProperty(prefix = "node.features", name = "recovery-enabled", havingValue = "true")
@RequestMapping("/recovery/fragments")
public class RecoveryController {

  private final TutorRecoveryService tutorRecoveryService;

  /**
   * Creates recovery controller.
   *
   * @param tutorRecoveryService service
   */
  public RecoveryController(final TutorRecoveryService tutorRecoveryService) {
    if (tutorRecoveryService == null) {
      throw new IllegalArgumentException("tutorRecoveryService must not be null");
    }
    this.tutorRecoveryService = tutorRecoveryService;
  }

  /**
   * Stores fragment under temporary tutor custody.
   *
   * @param payload request payload
   * @return stored metadata
   */
  @PostMapping
  public ResponseEntity<RecoveryStoredFragmentPayload> store(
      @RequestBody final RecoveryStoreFragmentPayload payload) {
    try {
      final RecoveryOrphanFragment stored = tutorRecoveryService.store(payload.toDomain());
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(RecoveryStoredFragmentPayload.fromDomain(stored));
    } catch (SecurityException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }

  /**
   * Stores fragment bytes under temporary tutor custody using binary body.
   *
   * @param fragmentId fragment id header
   * @param agreementId agreement id header
   * @param requesterNodeId requester node id header
   * @param requesterPublicKey requester public key header
   * @param checksumAlgorithm checksum algorithm header
   * @param checksum checksum header
   * @param payload binary payload body
   * @return stored metadata
   */
  @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<RecoveryStoredFragmentPayload> storeContent(
      @org.springframework.web.bind.annotation.RequestHeader("X-Fragment-Id")
          final String fragmentId,
      @org.springframework.web.bind.annotation.RequestHeader("X-Agreement-Id")
          final String agreementId,
      @org.springframework.web.bind.annotation.RequestHeader("X-Requester-Node-Id")
          final String requesterNodeId,
      @org.springframework.web.bind.annotation.RequestHeader("X-Requester-Public-Key")
          final String requesterPublicKey,
      @org.springframework.web.bind.annotation.RequestHeader("X-Checksum-Algorithm")
          final String checksumAlgorithm,
      @org.springframework.web.bind.annotation.RequestHeader("X-Checksum") final String checksum,
      @RequestBody final byte[] payload) {
    try {
      final RecoveryOrphanFragment stored =
          tutorRecoveryService.store(
              new TutorRecoveryService.StoreRecoveryFragmentRequest(
                  fragmentId,
                  agreementId,
                  requesterNodeId,
                  requesterPublicKey,
                  checksumAlgorithm,
                  checksum,
                  Base64.getEncoder().encodeToString(payload)));
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(RecoveryStoredFragmentPayload.fromDomain(stored));
    } catch (SecurityException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }

  /**
   * Reads stored fragment metadata while custody is valid.
   *
   * @param fragmentId fragment id
   * @return stored metadata
   */
  @GetMapping("/{id}")
  public ResponseEntity<RecoveryStoredFragmentPayload> get(
      @PathVariable("id") final String fragmentId) {
    try {
      final RecoveryOrphanFragment stored = tutorRecoveryService.get(fragmentId);
      return ResponseEntity.ok(RecoveryStoredFragmentPayload.fromDomain(stored));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.notFound().build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }

  /**
   * Reads stored fragment payload bytes while custody is valid.
   *
   * @param fragmentId fragment id
   * @return binary payload
   */
  @GetMapping(value = "/{id}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<byte[]> getContent(@PathVariable("id") final String fragmentId) {
    try {
      final RecoveryOrphanFragment stored = tutorRecoveryService.get(fragmentId);
      final byte[] content = tutorRecoveryService.getContent(fragmentId);
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .header("X-Checksum-Algorithm", stored.checksumAlgorithm())
          .header("X-Checksum", stored.checksum())
          .header("X-Size-Bytes", String.valueOf(stored.sizeBytes()))
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              "attachment; filename=\"" + stored.fragmentId() + ".bin\"")
          .body(content);
    } catch (NoSuchElementException ex) {
      return ResponseEntity.notFound().build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }

  /**
   * Reconstructs original file bytes from custodied RS fragment set.
   *
   * @param payload reconstruction payload
   * @return reconstructed binary payload
   */
  @PostMapping(value = "/reconstruct", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<byte[]> reconstruct(@RequestBody final RecoveryReconstructPayload payload) {
    try {
      final TutorRecoveryService.ReconstructedPayload reconstructed =
          tutorRecoveryService.reconstruct(payload.toDomain());
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .header("X-File-Id", reconstructed.fileId())
          .header("X-Checksum-Algorithm", reconstructed.checksumAlgorithm())
          .header("X-Checksum", reconstructed.checksum())
          .header("X-Size-Bytes", String.valueOf(reconstructed.payload().length))
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              "attachment; filename=\"" + reconstructed.fileId() + ".recovered.bin\"")
          .body(reconstructed.payload());
    } catch (NoSuchElementException ex) {
      return ResponseEntity.notFound().build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }
}
