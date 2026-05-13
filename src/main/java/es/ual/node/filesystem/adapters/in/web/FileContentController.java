package es.ual.node.filesystem.adapters.in.web;

import es.ual.node.discovery.application.DiscoveryUnreachableException;
import es.ual.node.filesystem.application.ContentTooLargeException;
import es.ual.node.filesystem.application.CustodianInsufficientStorageException;
import es.ual.node.filesystem.application.FileContentService;
import es.ual.node.filesystem.application.FileContentUploadResult;
import es.ual.node.filesystem.application.FileIrrecoverableException;
import es.ual.node.filesystem.application.FileUploadSessionService;
import es.ual.node.filesystem.application.FsContentConflictException;
import es.ual.node.filesystem.application.InconsistentFragmentPlacementException;
import es.ual.node.filesystem.application.InsufficientCustodiansException;
import es.ual.node.filesystem.application.QuotaExceededException;
import es.ual.node.filesystem.application.TutorManifestReplicationException;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.sync.application.SyncEventService;
import es.ual.node.userregistration.adapters.in.web.ApiErrorPayload;
import es.ual.node.userregistration.adapters.in.web.AuthenticatedUserRequestContext;
import es.ual.node.userregistration.application.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Client file content endpoints. */
@RestController
@RequestMapping("/files")
public class FileContentController {

  private final FileContentService fileContentService;
  private final FileUploadSessionService fileUploadSessionService;
  private final SyncEventService syncEventService;

  /**
   * Creates controller.
   *
   * @param fileContentService file content service
   * @param fileUploadSessionService resumable upload session service
   * @param syncEventService sync event service
   */
  public FileContentController(
      final FileContentService fileContentService,
      final FileUploadSessionService fileUploadSessionService,
      final SyncEventService syncEventService) {
    if (fileContentService == null
        || fileUploadSessionService == null
        || syncEventService == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.fileContentService = fileContentService;
    this.fileUploadSessionService = fileUploadSessionService;
    this.syncEventService = syncEventService;
  }

  /**
   * Creates resumable upload session.
   *
   * @param request HTTP request
   * @param payload create payload
   * @return upload session payload
   */
  @PostMapping("/upload-sessions")
  public ResponseEntity<?> createUploadSession(
      final HttpServletRequest request,
      @RequestBody final FileUploadSessionCreateRequestPayload payload) {
    try {
      final AuthenticatedUser authenticatedUser = AuthenticatedUserRequestContext.require(request);
      return ResponseEntity.ok(
          FileUploadSessionPayload.fromView(
              fileUploadSessionService.create(authenticatedUser.username(), payload.entryId())));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(404)
          .body(ApiErrorPayload.of("FILE_UPLOAD_ENTRY_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FILE_UPLOAD_SESSION_INVALID_REQUEST", ex.getMessage()));
    }
  }

  /**
   * Gets upload session status.
   *
   * @param request HTTP request
   * @param sessionId session id
   * @return upload session payload
   */
  @GetMapping("/upload-sessions/{id}")
  public ResponseEntity<?> getUploadSession(
      final HttpServletRequest request, @PathVariable("id") final String sessionId) {
    try {
      final AuthenticatedUser authenticatedUser = AuthenticatedUserRequestContext.require(request);
      return ResponseEntity.ok(
          FileUploadSessionPayload.fromView(
              fileUploadSessionService.get(authenticatedUser.username(), sessionId)));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(404)
          .body(ApiErrorPayload.of("FILE_UPLOAD_SESSION_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FILE_UPLOAD_SESSION_INVALID_REQUEST", ex.getMessage()));
    }
  }

  /**
   * Appends chunk into resumable upload session.
   *
   * @param request HTTP request
   * @param sessionId session id
   * @param offset chunk offset
   * @param content chunk bytes
   * @return upload session payload
   */
  @PutMapping(
      value = "/upload-sessions/{id}/chunks",
      consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<?> appendChunk(
      final HttpServletRequest request,
      @PathVariable("id") final String sessionId,
      @RequestParam("offset") final long offset,
      @RequestBody final byte[] content) {
    try {
      final AuthenticatedUser authenticatedUser = AuthenticatedUserRequestContext.require(request);
      return ResponseEntity.ok(
          FileUploadSessionPayload.fromView(
              fileUploadSessionService.appendChunk(
                  authenticatedUser.username(), sessionId, offset, content)));
    } catch (FsContentConflictException ex) {
      return ResponseEntity.status(409)
          .body(ApiErrorPayload.of("FILE_UPLOAD_CHUNK_CONFLICT", ex.getMessage()));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(404)
          .body(ApiErrorPayload.of("FILE_UPLOAD_SESSION_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FILE_UPLOAD_SESSION_INVALID_REQUEST", ex.getMessage()));
    }
  }

  /**
   * Completes resumable upload session.
   *
   * @param request HTTP request
   * @param sessionId session id
   * @return upload result payload
   */
  @PostMapping("/upload-sessions/{id}/complete")
  public ResponseEntity<?> completeUploadSession(
      final HttpServletRequest request, @PathVariable("id") final String sessionId) {
    try {
      final AuthenticatedUser authenticatedUser = AuthenticatedUserRequestContext.require(request);
      final FileContentUploadResult result =
          fileUploadSessionService.complete(authenticatedUser.username(), sessionId);
      syncEventService.publish(
          authenticatedUser.username(), "file-upload-complete", null, result.entryId());
      return ResponseEntity.ok(FileContentUploadPayload.fromResult(result));
    } catch (FsContentConflictException ex) {
      return ResponseEntity.status(409)
          .body(ApiErrorPayload.of("FILE_UPLOAD_COMPLETE_CONFLICT", ex.getMessage()));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(404)
          .body(ApiErrorPayload.of("FILE_UPLOAD_SESSION_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FILE_UPLOAD_SESSION_INVALID_REQUEST", ex.getMessage()));
    }
  }

  /**
   * Uploads binary content for a file entry. The request body is consumed via {@link
   * HttpServletRequest#getInputStream()} to keep the file from materializing in RAM, and {@code
   * Content-Length} is mandatory so the orchestrator can reserve quota up front. Missing or
   * negative {@code Content-Length} returns {@code 411 LENGTH_REQUIRED}.
   *
   * @param request HTTP request
   * @param entryId entry id
   * @return upload response payload
   */
  @PutMapping(value = "/entries/{id}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<?> upload(
      final HttpServletRequest request, @PathVariable("id") final String entryId) {
    try {
      final AuthenticatedUser authenticatedUser = AuthenticatedUserRequestContext.require(request);
      final long contentLength = request.getContentLengthLong();
      if (contentLength < 0) {
        return ResponseEntity.status(411)
            .body(
                ApiErrorPayload.of(
                    "LENGTH_REQUIRED", "Content-Length header is required for streaming uploads"));
      }
      final FileContentUploadResult result =
          fileContentService.uploadStreaming(
              authenticatedUser.username(), entryId, request.getInputStream(), contentLength);
      syncEventService.publish(
          authenticatedUser.username(), "file-upload-complete", null, result.entryId());
      return ResponseEntity.ok(FileContentUploadPayload.fromResult(result));
    } catch (ContentTooLargeException ex) {
      return ResponseEntity.status(413)
          .body(
              FileContentTooLargeErrorPayload.of(
                  "CONTENT_TOO_LARGE", ex.getMessage(), ex.sizeBytes(), ex.maxBytes()));
    } catch (QuotaExceededException ex) {
      return ResponseEntity.status(413)
          .body(
              QuotaExceededErrorPayload.of(
                  "QUOTA_EXCEEDED", ex.getMessage(), ex.requestedBytes(), ex.availableBytes()));
    } catch (InsufficientCustodiansException ex) {
      return ResponseEntity.status(503)
          .body(ApiErrorPayload.of("INSUFFICIENT_CUSTODIANS", ex.getMessage()));
    } catch (CustodianInsufficientStorageException ex) {
      return ResponseEntity.status(503)
          .body(ApiErrorPayload.of("CUSTODIAN_INSUFFICIENT_STORAGE", ex.getMessage()));
    } catch (DiscoveryUnreachableException ex) {
      return ResponseEntity.status(503)
          .body(ApiErrorPayload.of("DISCOVERY_UNREACHABLE", ex.getMessage()));
    } catch (TutorManifestReplicationException ex) {
      return ResponseEntity.status(503)
          .body(ApiErrorPayload.of("FILESYSTEM_TUTOR_REPLICATION_FAILED", ex.getMessage()));
    } catch (FsContentConflictException ex) {
      return ResponseEntity.status(409)
          .body(ApiErrorPayload.of("FILE_CONTENT_CONFLICT", ex.getMessage()));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(404)
          .body(ApiErrorPayload.of("FILE_ENTRY_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FILE_UPLOAD_INVALID_REQUEST", ex.getMessage()));
    } catch (java.io.IOException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FILE_UPLOAD_IO_ERROR", ex.getMessage()));
    }
  }

  /**
   * Downloads binary content for a file entry. Writes the reconstructed file directly to {@link
   * HttpServletResponse#getOutputStream()} block by block so the orchestrator can stream without
   * buffering the whole file in RAM. {@code Content-Length} and {@code X-Content-SHA256} are
   * sourced from the {@link FsEntry} metadata before streaming starts. Returns {@code null} on the
   * happy path to signal that the response has been fully written; errors are returned as regular
   * {@link ResponseEntity} so Spring can serialize them.
   *
   * @param request HTTP request
   * @param response HTTP response (the body is written directly to its output stream)
   * @param entryId entry id
   * @return {@code null} on success (response committed), or an error {@link ResponseEntity}
   */
  @GetMapping(value = "/entries/{id}/content")
  public ResponseEntity<?> download(
      final HttpServletRequest request,
      final HttpServletResponse response,
      @PathVariable("id") final String entryId) {
    try {
      final AuthenticatedUser authenticatedUser = AuthenticatedUserRequestContext.require(request);
      // ANTES de tocar el response: si esto lanza, los `catch` del método
      // pueden devolver JSON con el status correcto sin haber commiteado octet-stream headers.
      fileContentService.preflightDownload(authenticatedUser.username(), entryId);

      final FsEntry entry =
          fileContentService.requireActiveFile(authenticatedUser.username(), entryId);
      response.setStatus(HttpStatus.OK.value());
      response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
      response.setContentLengthLong(entry.sizeBytes());
      if (entry.checksum() != null) {
        response.setHeader("X-Content-SHA256", entry.checksum());
      }

      try {
        fileContentService.downloadStreaming(
            authenticatedUser.username(), entryId, response.getOutputStream());
      } catch (IOException ex) {
        // Response is already committed (headers + partial body); cannot send a JSON error.
        // Re-throw as runtime so Spring logs it and the client gets a truncated response.
        throw new IllegalStateException("I/O error streaming download", ex);
      }
      return null;
    } catch (InconsistentFragmentPlacementException ex) {
      return ResponseEntity.status(503)
          .body(ApiErrorPayload.of("FILE_INCONSISTENT_PLACEMENT", ex.getMessage()));
    } catch (FileIrrecoverableException ex) {
      return ResponseEntity.status(503)
          .body(ApiErrorPayload.of("FILE_IRRECOVERABLE", ex.getMessage()));
    } catch (FsContentConflictException ex) {
      return ResponseEntity.status(409)
          .body(ApiErrorPayload.of("FILE_CONTENT_CONFLICT", ex.getMessage()));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(404)
          .body(ApiErrorPayload.of("FILE_ENTRY_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("FILE_DOWNLOAD_INVALID_REQUEST", ex.getMessage()));
    }
  }
}
