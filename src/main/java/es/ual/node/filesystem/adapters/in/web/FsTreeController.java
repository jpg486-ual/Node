package es.ual.node.filesystem.adapters.in.web;

import es.ual.node.filesystem.application.FileContentService;
import es.ual.node.filesystem.application.FileSystemService;
import es.ual.node.filesystem.application.FsPathConflictException;
import es.ual.node.filesystem.application.FsTreeSnapshot;
import es.ual.node.filesystem.application.TutorManifestReplicationException;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.sync.application.SyncEventService;
import es.ual.node.userregistration.adapters.in.web.ApiErrorPayload;
import es.ual.node.userregistration.adapters.in.web.AuthenticatedUserRequestContext;
import es.ual.node.userregistration.application.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Client filesystem endpoints. */
@RestController
@RequestMapping("/fs")
public class FsTreeController {

  private final FileSystemService fileSystemService;
  private final FileContentService fileContentService;
  private final SyncEventService syncEventService;

  /**
   * Creates controller.
   *
   * @param fileSystemService filesystem service
   * @param fileContentService file content service
   * @param syncEventService sync event service
   */
  public FsTreeController(
      final FileSystemService fileSystemService,
      final FileContentService fileContentService,
      final SyncEventService syncEventService) {
    if (fileSystemService == null || fileContentService == null || syncEventService == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.fileSystemService = fileSystemService;
    this.fileContentService = fileContentService;
    this.syncEventService = syncEventService;
  }

  /**
   * Returns current user filesystem tree snapshot.
   *
   * @param request HTTP request
   * @return filesystem tree payload
   */
  @GetMapping("/tree")
  public ResponseEntity<?> tree(
      final HttpServletRequest request,
      @RequestParam(name = "sinceCursor", required = false) final Long sinceCursor) {
    try {
      final AuthenticatedUser authenticatedUser = AuthenticatedUserRequestContext.require(request);
      final FsTreeSnapshot snapshot =
          fileSystemService.tree(authenticatedUser.username(), sinceCursor);
      final List<FsEntryPayload> entries =
          snapshot.entries().stream().map(FsEntryPayload::fromDomain).toList();
      return ResponseEntity.ok(
          new FsTreePayload(
              snapshot.username(), snapshot.cursor(), snapshot.snapshotAt(), entries));
    } catch (IllegalArgumentException | NoSuchElementException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FS_TREE_INVALID_REQUEST", ex.getMessage()));
    }
  }

  /**
   * Upserts a filesystem metadata entry for authenticated user.
   *
   * @param request HTTP request
   * @param payload upsert payload
   * @return persisted entry payload
   */
  @PostMapping("/entries")
  public ResponseEntity<?> upsert(
      final HttpServletRequest request, @RequestBody final FsUpsertEntryRequestPayload payload) {
    try {
      final AuthenticatedUser authenticatedUser = AuthenticatedUserRequestContext.require(request);
      final FsEntry entry =
          fileSystemService.upsert(payload.toApplication(authenticatedUser.username()));
      syncEventService.publish(
          authenticatedUser.username(),
          "fs-upsert",
          entry.updatedAt().toEpochMilli(),
          entry.entryId());
      return ResponseEntity.ok(FsEntryPayload.fromDomain(entry));
    } catch (FsPathConflictException ex) {
      return ResponseEntity.status(409)
          .body(ApiErrorPayload.of("FS_PATH_CONFLICT", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FS_UPSERT_INVALID_REQUEST", ex.getMessage()));
    }
  }

  /**
   * Renames or moves an existing entry.
   *
   * @param request HTTP request
   * @param entryId entry id
   * @param payload patch payload
   * @return patched entry payload
   */
  @PatchMapping("/entries/{id}")
  public ResponseEntity<?> patch(
      final HttpServletRequest request,
      @PathVariable("id") final String entryId,
      @RequestBody final FsPatchEntryRequestPayload payload) {
    try {
      final AuthenticatedUser authenticatedUser = AuthenticatedUserRequestContext.require(request);
      final FsEntry entry =
          fileSystemService.patch(payload.toApplication(authenticatedUser.username(), entryId));
      syncEventService.publish(
          authenticatedUser.username(),
          "fs-move",
          entry.updatedAt().toEpochMilli(),
          entry.entryId());
      return ResponseEntity.ok(FsEntryPayload.fromDomain(entry));
    } catch (FsPathConflictException ex) {
      return ResponseEntity.status(409)
          .body(ApiErrorPayload.of("FS_PATH_CONFLICT", ex.getMessage()));
    } catch (TutorManifestReplicationException ex) {
      return ResponseEntity.status(503)
          .body(ApiErrorPayload.of("FILESYSTEM_TUTOR_REPLICATION_FAILED", ex.getMessage()));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(404)
          .body(ApiErrorPayload.of("FS_ENTRY_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FS_PATCH_INVALID_REQUEST", ex.getMessage()));
    }
  }

  /**
   * Bulk-deletes a sub-tree. Body: {@code {path}}. Marks every entry as a tombstone, purges the
   * local manifests, releases quota and best-effort-removes the affected manifests at the tutor in
   * a single roundtrip. If the tutor is unreachable, the local tombstones still persist, the
   * cross-check renewal are the safety net.
   */
  @PostMapping("/entries/delete-subtree")
  public ResponseEntity<?> deleteSubtree(
      final HttpServletRequest request, @RequestBody final FsDeleteSubtreeRequestPayload payload) {
    if (payload == null || payload.path() == null) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FS_DELETE_INVALID_REQUEST", "path is required"));
    }
    try {
      final AuthenticatedUser authenticatedUser = AuthenticatedUserRequestContext.require(request);
      final java.util.List<FsEntry> deleted =
          fileSystemService.deleteSubtree(authenticatedUser.username(), payload.path());
      syncEventService.publish(
          authenticatedUser.username(),
          "fs-delete-subtree",
          deleted.isEmpty() ? null : deleted.get(0).updatedAt().toEpochMilli(),
          deleted.isEmpty() ? null : deleted.get(0).entryId());
      final java.util.List<FsEntryPayload> outPayload =
          deleted.stream().map(FsEntryPayload::fromDomain).toList();
      return ResponseEntity.ok(new FsDeleteSubtreeResponsePayload(outPayload));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(404)
          .body(ApiErrorPayload.of("FS_ENTRY_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FS_DELETE_INVALID_REQUEST", ex.getMessage()));
    }
  }

  /**
   * Renames or moves a sub-tree atomically. Body: {@code {fromPath, toPath}}. Costs 1 tutor
   * exchange instead of N. If the tutor refuses the bulk update, no local entry changes, the client
   * receives 503 and may retry later.
   */
  @PostMapping("/entries/move-subtree")
  public ResponseEntity<?> moveSubtree(
      final HttpServletRequest request, @RequestBody final FsMoveSubtreeRequestPayload payload) {
    if (payload == null || payload.fromPath() == null || payload.toPath() == null) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FS_MOVE_INVALID_REQUEST", "fromPath and toPath are required"));
    }
    try {
      final AuthenticatedUser authenticatedUser = AuthenticatedUserRequestContext.require(request);
      final java.util.List<FsEntry> moved =
          fileSystemService.moveSubtree(
              authenticatedUser.username(), payload.fromPath(), payload.toPath());
      syncEventService.publish(
          authenticatedUser.username(),
          "fs-move-subtree",
          moved.isEmpty() ? null : moved.get(0).updatedAt().toEpochMilli(),
          moved.isEmpty() ? null : moved.get(0).entryId());
      final java.util.List<FsEntryPayload> outPayload =
          moved.stream().map(FsEntryPayload::fromDomain).toList();
      return ResponseEntity.ok(new FsMoveSubtreeResponsePayload(outPayload));
    } catch (FsPathConflictException ex) {
      return ResponseEntity.status(409)
          .body(ApiErrorPayload.of("FS_PATH_CONFLICT", ex.getMessage()));
    } catch (TutorManifestReplicationException ex) {
      return ResponseEntity.status(503)
          .body(ApiErrorPayload.of("FILESYSTEM_TUTOR_REPLICATION_FAILED", ex.getMessage()));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(404)
          .body(ApiErrorPayload.of("FS_ENTRY_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FS_MOVE_INVALID_REQUEST", ex.getMessage()));
    }
  }

  /**
   * Marks an existing entry as deleted.
   *
   * @param request HTTP request
   * @param entryId entry id
   * @return deleted entry payload
   */
  @DeleteMapping("/entries/{id}")
  public ResponseEntity<?> delete(
      final HttpServletRequest request, @PathVariable("id") final String entryId) {
    try {
      final AuthenticatedUser authenticatedUser = AuthenticatedUserRequestContext.require(request);
      final FsEntry entry = fileSystemService.delete(authenticatedUser.username(), entryId);
      if (entry.entryType() == FsEntryType.FILE) {
        fileContentService.deleteContent(authenticatedUser.username(), entry.entryId());
      }
      syncEventService.publish(
          authenticatedUser.username(),
          "fs-delete",
          entry.updatedAt().toEpochMilli(),
          entry.entryId());
      return ResponseEntity.ok(FsEntryPayload.fromDomain(entry));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(404)
          .body(ApiErrorPayload.of("FS_ENTRY_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FS_DELETE_INVALID_REQUEST", ex.getMessage()));
    }
  }
}
