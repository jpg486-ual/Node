package es.ual.node.filesystem.adapters.in.web;

/** Request payload for creating resumable upload session. */
public record FileUploadSessionCreateRequestPayload(String entryId) {}
