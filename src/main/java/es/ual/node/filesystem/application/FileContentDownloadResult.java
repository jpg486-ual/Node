package es.ual.node.filesystem.application;

/** Download result for file content reads. */
public record FileContentDownloadResult(String entryId, byte[] content, String checksum) {}
