package es.ual.node.sync.adapters.in.web;

import es.ual.node.filesystem.adapters.in.web.FsEntryPayload;
import es.ual.node.filesystem.application.FileSystemService;
import es.ual.node.filesystem.application.FsTreeSnapshot;
import es.ual.node.sync.application.SyncEventService;
import es.ual.node.userregistration.adapters.in.web.ApiErrorPayload;
import es.ual.node.userregistration.adapters.in.web.AuthenticatedUserRequestContext;
import es.ual.node.userregistration.application.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Client sync endpoints. */
@RestController
@RequestMapping("/sync")
public class SyncController {

  private final FileSystemService fileSystemService;
  private final SyncEventService syncEventService;

  /**
   * Creates controller.
   *
   * @param fileSystemService filesystem service
   * @param syncEventService event service
   */
  public SyncController(
      final FileSystemService fileSystemService, final SyncEventService syncEventService) {
    if (fileSystemService == null || syncEventService == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.fileSystemService = fileSystemService;
    this.syncEventService = syncEventService;
  }

  /**
   * Returns incremental filesystem changes since cursor.
   *
   * @param request HTTP request
   * @param since lower cursor bound
   * @return changes payload
   */
  @GetMapping("/changes")
  public ResponseEntity<?> changes(
      final HttpServletRequest request, @RequestParam(name = "since") final long since) {
    try {
      final AuthenticatedUser user = AuthenticatedUserRequestContext.require(request);
      final FsTreeSnapshot snapshot = fileSystemService.tree(user.username(), since);
      final List<FsEntryPayload> changes =
          snapshot.entries().stream().map(FsEntryPayload::fromDomain).toList();
      return ResponseEntity.ok(
          new SyncChangesPayload(
              snapshot.username(), snapshot.cursor(), snapshot.snapshotAt(), changes));
    } catch (IllegalArgumentException | NoSuchElementException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("SYNC_INVALID_SINCE", ex.getMessage()));
    }
  }

  /**
   * Opens SSE channel for user sync notifications.
   *
   * @param request HTTP request
   * @return SSE emitter
   */
  @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(final HttpServletRequest request) {
    final AuthenticatedUser user = AuthenticatedUserRequestContext.require(request);
    final long cursor = fileSystemService.currentCursor(user.username());
    return syncEventService.subscribe(user.username(), cursor);
  }
}
