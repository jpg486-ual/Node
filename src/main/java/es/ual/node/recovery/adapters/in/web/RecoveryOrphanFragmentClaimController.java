package es.ual.node.recovery.adapters.in.web;

import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.recovery.application.RecoveryOrphanClaimService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.NoSuchElementException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tutor-side controller que expone el flujo claim+ACK del origen recovered:
 *
 * <ul>
 *   <li>{@code POST /recovery/orphan-fragments/{fragmentId}/claim}: devuelve los bytes (200 con
 *       headers de checksum + size). El tutor NO borra todavía.
 *   <li>{@code POST /recovery/orphan-fragments/{fragmentId}/ack}: confirma descarga, tutor borra
 *       metadata + payload (204).
 * </ul>
 */
@RestController
@ConditionalOnProperty(prefix = "node.features", name = "recovery-enabled", havingValue = "true")
@RequestMapping("/recovery/orphan-fragments")
public class RecoveryOrphanFragmentClaimController {

  /** Header devuelto en {@code claim} con el checksum del payload. */
  public static final String HEADER_CHECKSUM = "X-Checksum";

  /** Header devuelto en {@code claim} con el algoritmo de checksum. */
  public static final String HEADER_CHECKSUM_ALGORITHM = "X-Checksum-Algorithm";

  /** Header devuelto en {@code claim} con el size del payload. */
  public static final String HEADER_SIZE_BYTES = "X-Size-Bytes";

  private final RecoveryOrphanClaimService service;

  /** Creates controller. */
  public RecoveryOrphanFragmentClaimController(final RecoveryOrphanClaimService service) {
    if (service == null) {
      throw new IllegalArgumentException("service must not be null");
    }
    this.service = service;
  }

  /**
   * Claim step — devuelve los bytes del orphan + headers de integridad. El tutor NO borra. El
   * caller debe llamar {@link #ack(String, HttpServletRequest)} cuando confirme descarga.
   */
  @PostMapping(value = "/{fragmentId}/claim", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<byte[]> claim(
      @PathVariable("fragmentId") final String fragmentId, final HttpServletRequest request) {
    final String nodeId = request.getHeader(RequestSignatureValidator.HEADER_NODE_ID);
    if (nodeId == null || nodeId.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    try {
      final RecoveryOrphanClaimService.ClaimResult result = service.claim(fragmentId, nodeId);
      return ResponseEntity.ok()
          .header(HEADER_CHECKSUM, result.orphan().checksum())
          .header(HEADER_CHECKSUM_ALGORITHM, result.orphan().checksumAlgorithm())
          .header(HEADER_SIZE_BYTES, String.valueOf(result.orphan().sizeBytes()))
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .body(result.payload());
    } catch (SecurityException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    } catch (NoSuchElementException ex) {
      return ResponseEntity.notFound().build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }

  /** ACK step — caller confirma descarga, tutor borra orphan + payload. */
  @PostMapping("/{fragmentId}/ack")
  public ResponseEntity<Void> ack(
      @PathVariable("fragmentId") final String fragmentId, final HttpServletRequest request) {
    final String nodeId = request.getHeader(RequestSignatureValidator.HEADER_NODE_ID);
    if (nodeId == null || nodeId.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    try {
      service.ack(fragmentId, nodeId);
      return ResponseEntity.noContent().build();
    } catch (SecurityException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }
}
