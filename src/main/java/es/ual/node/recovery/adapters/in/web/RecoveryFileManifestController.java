package es.ual.node.recovery.adapters.in.web;

import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.recovery.application.TutorFileManifestCustodyService;
import es.ual.node.recovery.domain.CustodiedFileManifest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP adapter for proactive tutor custody of FileManifest. Sibling of {@link RecoveryController}:
 * same role-gating, same whitelist authentication, but operates on {@code /recovery/file-manifests}
 * instead of {@code /recovery/fragments}.
 */
@RestController
@ConditionalOnProperty(prefix = "node.features", name = "recovery-enabled", havingValue = "true")
@RequestMapping("/recovery/file-manifests")
public class RecoveryFileManifestController {

  private final TutorFileManifestCustodyService service;

  /**
   * Creates controller.
   *
   * @param service tutor file manifest custody service
   */
  public RecoveryFileManifestController(final TutorFileManifestCustodyService service) {
    if (service == null) {
      throw new IllegalArgumentException("service must not be null");
    }
    this.service = service;
  }

  /** Stores a manifest under tutor custody. */
  @PostMapping
  public ResponseEntity<CustodiedFileManifestPayload> store(
      @RequestBody final RecoveryStoreFileManifestPayload payload) {
    try {
      final CustodiedFileManifest stored = service.store(payload.toDomain());
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(CustodiedFileManifestPayload.fromDomain(stored));
    } catch (SecurityException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    } catch (IllegalArgumentException | NullPointerException ex) {
      return ResponseEntity.badRequest().build();
    }
  }

  /**
   * Lists manifests custodied for the requesting node.
   *
   * <p>The requester is identified exclusively by the {@code X-Node-Id} signature header. The tutor
   * returns only manifests whose {@code requester_node_id} matches. No username or path parameter
   * is accepted: a node cannot query manifests of a different node.
   */
  /**
   * Updates the {@code directoryPath} and {@code originalFileName} of a custodied manifest after a
   * rename/move at the origin. The caller is identified by the {@code X-Node-Id} signature header
   * must match the manifest's {@code requesterNodeId}. The TTL is preserved.
   *
   * <p>Errors:
   *
   * <ul>
   *   <li>{@code 200 OK}: successful update; body carries the updated manifest payload.
   *   <li>{@code 400 Bad Request}: blank fileId, missing caller header, blank/malformed {@code
   *       directoryPath} or {@code originalFileName}.
   *   <li>{@code 403 Forbidden}: caller does not own the manifest.
   *   <li>{@code 404 Not Found}: no manifest exists for the given {@code fileId}.
   * </ul>
   */
  @PatchMapping("/{fileId}")
  public ResponseEntity<CustodiedFileManifestPayload> updatePath(
      @PathVariable("fileId") final String fileId,
      final HttpServletRequest request,
      @RequestBody final RecoveryUpdatePathPayload payload) {
    final String nodeId = request.getHeader(RequestSignatureValidator.HEADER_NODE_ID);
    if (nodeId == null || nodeId.isBlank() || payload == null) {
      return ResponseEntity.badRequest().build();
    }
    try {
      final CustodiedFileManifest updated =
          service.updatePath(fileId, nodeId, payload.directoryPath(), payload.originalFileName());
      return ResponseEntity.ok(CustodiedFileManifestPayload.fromDomain(updated));
    } catch (SecurityException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }

  @GetMapping
  public ResponseEntity<RecoveryFileManifestListPayload> listByRequestingNode(
      final HttpServletRequest request) {
    final String nodeId = request.getHeader(RequestSignatureValidator.HEADER_NODE_ID);
    if (nodeId == null || nodeId.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    final List<CustodiedFileManifest> manifests = service.listByRequesterNodeId(nodeId);
    final List<CustodiedFileManifestPayload> payload =
        manifests.stream().map(CustodiedFileManifestPayload::fromDomain).toList();
    return ResponseEntity.ok(new RecoveryFileManifestListPayload(payload));
  }

  /**
   * Endpoint inverso ("supervisado pregunta al tutor"). El supervisado, si lleva tiempo sin recibir
   * keep-list, llama aquí pidiendo lista completa de fileIds que el tutor custodia para él. Compara
   * con su {@code client_file_manifest} local y re-emite los faltantes.
   *
   * <p>Auth: el caller {@code X-Node-Id} debe matchear el {@code requesterNodeId} de los manifests
   * devueltos.
   */
  @GetMapping("/inventory")
  public ResponseEntity<TutorManifestInventoryResponsePayload> inventory(
      final HttpServletRequest request) {
    final String nodeId = request.getHeader(RequestSignatureValidator.HEADER_NODE_ID);
    if (nodeId == null || nodeId.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    final List<String> fileIds =
        service.listByRequesterNodeId(nodeId).stream().map(CustodiedFileManifest::fileId).toList();
    return ResponseEntity.ok(new TutorManifestInventoryResponsePayload(fileIds));
  }

  /**
   * Removes a manifest from tutor custody. The caller is identified by the {@code X-Node-Id}
   * signature header (must match the manifest's {@code requesterNodeId}).
   *
   * <p>Errors:
   *
   * <ul>
   *   <li>{@code 204 No Content}: successful delete.
   *   <li>{@code 400 Bad Request}: blank fileId or missing caller header.
   *   <li>{@code 403 Forbidden}: caller does not own the manifest.
   *   <li>{@code 404 Not Found}: no manifest exists for the given {@code fileId}.
   * </ul>
   */
  @DeleteMapping("/{fileId}")
  public ResponseEntity<Void> delete(
      @PathVariable("fileId") final String fileId, final HttpServletRequest request) {
    final String nodeId = request.getHeader(RequestSignatureValidator.HEADER_NODE_ID);
    if (nodeId == null || nodeId.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    try {
      service.delete(fileId, nodeId);
      return ResponseEntity.noContent().build();
    } catch (SecurityException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }
}
