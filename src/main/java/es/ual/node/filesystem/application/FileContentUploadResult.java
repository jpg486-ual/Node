package es.ual.node.filesystem.application;

/** Upload result for file content writes. */
public record FileContentUploadResult(String entryId, long sizeBytes, String checksum) {}
