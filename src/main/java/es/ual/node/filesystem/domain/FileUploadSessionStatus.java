package es.ual.node.filesystem.domain;

/** Status of resumable upload session. */
public enum FileUploadSessionStatus {
  OPEN,
  COMPLETED,
  ABORTED
}
