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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sibling of {@link RecoveryFileManifestController} that hosts the bulk-update endpoint on a path
 * that does NOT collide with the {@code @PatchMapping("/{fileId}")} of the parent controller.
 * Mounting this on the same {@code /recovery/file-manifests} root as a {@code /_bulk} suffix
 * shadowed it via Spring's path-variable matcher (any segment, including {@code "_bulk"}, satisfies
 * {@code /{fileId}}). Hosting it on the disjoint root {@code /recovery/file-manifests-bulk} is the
 * cheapest fix that preserves API discoverability without encoding tricks.
 *
 * <p>Used by the origin's sub-tree MOVE flow to replicate path changes for many children in a
 * single signed roundtrip with atomicidad estricta, todo o nada (RFC 7807 / "Transactional outbox",
 * Hohpe & Woolf 2003).
 */
@RestController
@ConditionalOnProperty(prefix = "node.features", name = "recovery-enabled", havingValue = "true")
@RequestMapping("/recovery/file-manifests-bulk")
public class RecoveryFileManifestBulkController {

  private final TutorFileManifestCustodyService service;

  /** Creates controller. */
  public RecoveryFileManifestBulkController(final TutorFileManifestCustodyService service) {
    if (service == null) {
      throw new IllegalArgumentException("service must not be null");
    }
    this.service = service;
  }

  /**
   * Applies N path/filename updates to custodied manifests in a single signed roundtrip with
   * atomicidad estricta, todo o nada. All entries must belong to the same caller node (matched via
   * signed {@code X-Node-Id}). Ownership pre-validation precedes any write.
   *
   * <p>Errors:
   *
   * <ul>
   *   <li>{@code 200 OK}: successful update; body carries the array of updated manifests.
   *   <li>{@code 400 Bad Request}: empty payload, missing caller header, or any entry with
   *       blank/malformed {@code directoryPath} or {@code originalFileName}.
   *   <li>{@code 403 Forbidden}: any entry's manifest is not owned by the caller.
   *   <li>{@code 404 Not Found}: any entry's {@code fileId} unknown at the tutor.
   * </ul>
   */
  @PatchMapping
  public ResponseEntity<RecoveryFileManifestListPayload> updatePathBulk(
      final HttpServletRequest request, @RequestBody final RecoveryBulkUpdatePathPayload payload) {
    final String nodeId = request.getHeader(RequestSignatureValidator.HEADER_NODE_ID);
    if (nodeId == null
        || nodeId.isBlank()
        || payload == null
        || payload.entries() == null
        || payload.entries().isEmpty()) {
      return ResponseEntity.badRequest().build();
    }
    final List<TutorFileManifestCustodyService.BulkUpdateEntry> domainEntries =
        payload.entries().stream()
            .map(
                e ->
                    new TutorFileManifestCustodyService.BulkUpdateEntry(
                        e.fileId(), e.directoryPath(), e.originalFileName()))
            .toList();
    try {
      final List<CustodiedFileManifest> updated = service.updatePathBulk(nodeId, domainEntries);
      final List<CustodiedFileManifestPayload> outPayload =
          updated.stream().map(CustodiedFileManifestPayload::fromDomain).toList();
      return ResponseEntity.ok(new RecoveryFileManifestListPayload(outPayload));
    } catch (SecurityException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }

  /**
   * Removes N custodied manifests in a single signed roundtrip. Idempotent: missing {@code fileId}s
   * do NOT abort the operation, they are counted in the response. All existing fileIds must belong
   * to the caller (matched via signed {@code X-Node-Id}); any cross-owner entry rejects the entire
   * bulk before any delete is performed.
   *
   * <p>Errors:
   *
   * <ul>
   *   <li>{@code 200 OK}: body carries {@code {deletedCount, missingCount}}.
   *   <li>{@code 400 Bad Request}: empty payload, missing caller header, or any blank fileId.
   *   <li>{@code 403 Forbidden}: any existing manifest is not owned by the caller.
   * </ul>
   */
  @DeleteMapping
  public ResponseEntity<TutorFileManifestCustodyService.DeleteBulkResult> deleteBulk(
      final HttpServletRequest request, @RequestBody final RecoveryBulkDeletePayload payload) {
    final String nodeId = request.getHeader(RequestSignatureValidator.HEADER_NODE_ID);
    if (nodeId == null
        || nodeId.isBlank()
        || payload == null
        || payload.fileIds() == null
        || payload.fileIds().isEmpty()) {
      return ResponseEntity.badRequest().build();
    }
    try {
      final TutorFileManifestCustodyService.DeleteBulkResult result =
          service.deleteBulk(nodeId, payload.fileIds());
      return ResponseEntity.ok(result);
    } catch (SecurityException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest().build();
    }
  }
}
