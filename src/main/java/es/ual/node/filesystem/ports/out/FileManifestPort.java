package es.ual.node.filesystem.ports.out;

import es.ual.node.negotiation.domain.FileManifest;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for the file manifest stored at the origin node.
 *
 * <p>The origin keeps the manifest locally so it can reconstruct the file from custodian fragments
 * at download time. The manifest is the same {@link FileManifest} aggregate that travels in
 * negotiation contracts and that the tutor proactively custodies. One manifest per {@code fileId}.
 */
public interface FileManifestPort {

  /**
   * Persists the manifest associated with the given user and FS entry.
   *
   * @param manifest manifest to persist
   * @param username owner username
   * @param entryId FS entry identifier (linkage with FsEntry.fileId)
   */
  void save(FileManifest manifest, String username, String entryId);

  /**
   * Finds manifest by file identifier.
   *
   * @param fileId file identifier
   * @return optional manifest
   */
  Optional<FileManifest> findByFileId(String fileId);

  /**
   * Removes the manifest record (called when the FsEntry is deleted permanently).
   *
   * @param fileId file identifier
   */
  void deleteByFileId(String fileId);

  /**
   * Returns all file ids of locally-stored manifests. Consumido por el endpoint {@code GET
   * /ops/tutor/manifest-keep-list} (origen → tutor): el tutor consulta al origen pidiendo la
   * whitelist de manifests a conservar; el origen responde con la lista completa de fileIds que
   * tiene en {@code client_file_manifest}.
   *
   * @return list of all file ids locally persisted (no order guarantee)
   */
  List<String> findAllFileIds();
}
