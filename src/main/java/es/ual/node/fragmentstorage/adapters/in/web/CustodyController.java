package es.ual.node.fragmentstorage.adapters.in.web;

import es.ual.node.fragmentstorage.application.CustodyInsufficientStorageException;
import es.ual.node.fragmentstorage.application.FragmentCustodyAuthorizationException;
import es.ual.node.fragmentstorage.application.FragmentCustodyService;
import es.ual.node.fragmentstorage.application.FragmentCustodyService.StoreFragmentRequest;
import es.ual.node.fragmentstorage.domain.CustodyFragment;
import es.ual.node.userregistration.adapters.in.web.ApiErrorPayload;
import java.util.NoSuchElementException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * General-purpose fragment custody endpoint.
 *
 * <p>Active on every node that participates in peer-side fragment custody ({@code
 * node.features.custody-enabled=true}), independently of whether the node also fulfils the tutor
 * role. This separates "I accept fragments from authorized network peers" (custody) from "I am the
 * tutor that receives orphan escalations" (recovery).
 */
@RestController
@ConditionalOnProperty(prefix = "node.features", name = "custody-enabled", havingValue = "true")
@RequestMapping("/custody/fragments")
public class CustodyController {

  private final FragmentCustodyService fragmentCustodyService;

  /** Creates controller. */
  public CustodyController(final FragmentCustodyService fragmentCustodyService) {
    if (fragmentCustodyService == null) {
      throw new IllegalArgumentException("fragmentCustodyService must not be null");
    }
    this.fragmentCustodyService = fragmentCustodyService;
  }

  /**
   * Receives a fragment binary from an authorized network peer. Signed at the transport layer by
   * the upstream {@code RequestSignatureValidator} (already configured to cover {@code /ops/**} and
   * {@code /custody/**}).
   */
  @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<?> store(
      @RequestHeader("X-Fragment-Id") final String fragmentId,
      @RequestHeader("X-Agreement-Id") final String agreementId,
      @RequestHeader("X-Sender-Node-Id") final String senderNodeId,
      @RequestHeader("X-Sender-Public-Key") final String senderPublicKey,
      @RequestHeader("X-Checksum-Algorithm") final String checksumAlgorithm,
      @RequestHeader("X-Checksum") final String checksum,
      @RequestHeader(value = "X-Custody-Seconds", required = false) final String custodySeconds,
      @RequestBody final byte[] payload) {
    try {
      final StoreFragmentRequest request =
          new StoreFragmentRequest(
              fragmentId,
              agreementId,
              senderNodeId,
              senderPublicKey,
              checksumAlgorithm,
              checksum,
              payload,
              parseCustodySeconds(custodySeconds));
      final CustodyFragment stored = fragmentCustodyService.store(request);
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(CustodyStoredFragmentPayload.fromDomain(stored));
    } catch (FragmentCustodyAuthorizationException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(ApiErrorPayload.of("CUSTODY_SENDER_NOT_AUTHORIZED", ex.getMessage()));
    } catch (CustodyInsufficientStorageException ex) {
      return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE)
          .body(ApiErrorPayload.of("CUSTODY_INSUFFICIENT_STORAGE", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("CUSTODY_INVALID_REQUEST", ex.getMessage()));
    }
  }

  /**
   * Lists fragments custodied by this node on behalf of {@code nodeId}. Used by an origin in
   * recovery to discover which placements are still alive at this peer . The caller's signed {@code
   * X-Node-Id} header must match the path variable; cross-owner inventory probing is rejected with
   * 403 to avoid leaking custody state.
   */
  @GetMapping(value = "/by-requester/{nodeId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> listByRequester(
      @PathVariable("nodeId") final String nodeId,
      @RequestHeader("X-Node-Id") final String callerNodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("CUSTODY_INVALID_REQUEST", "nodeId must not be blank"));
    }
    if (callerNodeId == null || callerNodeId.isBlank()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ApiErrorPayload.of("CUSTODY_CALLER_NOT_IDENTIFIED", "X-Node-Id header required"));
    }
    if (!callerNodeId.trim().equals(nodeId.trim())) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(
              ApiErrorPayload.of(
                  "CUSTODY_INVENTORY_FORBIDDEN", "caller may only query its own inventory"));
    }
    return ResponseEntity.ok(
        CustodyInventoryListPayload.of(
            nodeId.trim(), fragmentCustodyService.listInventoryByRequester(nodeId.trim())));
  }

  /**
   * Returns the binary content of a custodied fragment to an authorized requester (typically the
   * origin node performing a download reconstruction).
   */
  @GetMapping(value = "/{fragmentId}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<?> content(@PathVariable("fragmentId") final String fragmentId) {
    try {
      final CustodyFragment metadata = fragmentCustodyService.findMetadata(fragmentId);
      final byte[] bytes = fragmentCustodyService.findContent(fragmentId);
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .contentLength(bytes.length)
          .header("X-Content-SHA256", metadata.checksum())
          .header("X-Fragment-Id", metadata.fragmentId())
          .body(bytes);
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(ApiErrorPayload.of("CUSTODY_FRAGMENT_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("CUSTODY_INVALID_REQUEST", ex.getMessage()));
    }
  }

  private Long parseCustodySeconds(final String header) {
    if (header == null || header.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(header.trim());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("X-Custody-Seconds must be a long", exception);
    }
  }
}
