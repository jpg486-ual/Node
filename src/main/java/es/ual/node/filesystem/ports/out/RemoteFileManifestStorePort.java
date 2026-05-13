package es.ual.node.filesystem.ports.out;

import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.negotiation.domain.FileManifest;
import java.util.List;

/**
 * Outbound port used by the origin to replicate a {@link FileManifest} (with embedded {@link
 * FragmentPlacement} routing) to its tutor right after a successful upload distribution.
 *
 * <p>The placements travel embedded inside the manifest's wire JSON as an opaque blob so the tutor
 * never reads them. Their consumer is the origin itself when it recovers from a fatal disk loss: it
 * pulls its manifests via {@code GET /recovery/file-manifests} and uses the embedded placements to
 * contact peers and download the {@code k} fragments needed for reconstruction.
 *
 * <p>Both methods are synchronous and fail-closed: if the tutor cannot be reached, callers are
 * expected to abort the operation and roll back local state. The strong invariant is that a
 * successful upload implies the manifest was replicated.
 */
public interface RemoteFileManifestStorePort {

  /**
   * Replicates the manifest to the tutor.
   *
   * @param manifest validated file manifest
   * @param placements one entry per fragment (ordered by {@code blockIndex}, {@code
   *     fragmentIndex}); embedded in {@code manifest_json} on the wire
   * @param tutorBaseUrl base URL of the tutor, e.g. {@code http://node2:8080}
   * @throws IllegalArgumentException if any required argument is blank/null/empty
   * @throws IllegalStateException if the HTTP exchange fails or returns non-2xx
   */
  void store(FileManifest manifest, List<FragmentPlacement> placements, String tutorBaseUrl);

  /**
   * Removes the manifest from the tutor. Invoked from {@code FileSystemService.delete()} after the
   * local manifest is purged. Idempotent: a 404 response is treated as success (manifest already
   * gone).
   *
   * @param fileId manifest identifier
   * @param tutorBaseUrl base URL of the tutor
   * @throws IllegalArgumentException if any argument is blank
   * @throws IllegalStateException if the HTTP exchange fails on a non-404 error
   */
  void delete(String fileId, String tutorBaseUrl);

  /**
   * Updates the {@code directoryPath} and {@code originalFileName} of a custodied manifest after a
   * rename/move at origin. Invoked from {@code FileSystemService.patch(...)} BEFORE persisting the
   * local change; if this call fails the local rename is aborted (fail-closed — the strong
   * invariant is that the tutor's view of {@code directoryPath} reflects the current FS state).
   *
   * @param fileId manifest identifier
   * @param newDirectoryPath new path (already prefixed with {@code /<username>} the tutor validates
   *     the regex but treats it as opaque otherwise)
   * @param newOriginalFileName new file name (no path separators)
   * @param tutorBaseUrl base URL of the tutor
   * @throws IllegalArgumentException if any argument is blank/null
   * @throws IllegalStateException if the HTTP exchange fails or returns non-2xx
   */
  void updatePath(
      String fileId, String newDirectoryPath, String newOriginalFileName, String tutorBaseUrl);

  /**
   * Bulk variant of {@link #updatePath}. Sends N updates in a single signed roundtrip. The tutor
   * applies them atomically (todo o nada). Used by the origin's sub-tree MOVE flow to pay 1 network
   * exchange instead of N.
   *
   * @param entries non-empty list of single-entry updates; all must belong to this origin
   * @param tutorBaseUrl base URL of the tutor
   * @throws IllegalArgumentException if any argument is null/blank/empty
   * @throws IllegalStateException if the HTTP exchange fails or returns non-2xx
   */
  void updatePathBulk(List<BulkUpdateEntry> entries, String tutorBaseUrl);

  /**
   * Single update entry used by {@link #updatePathBulk}.
   *
   * @param fileId manifest identifier
   * @param newDirectoryPath new path (with the {@code /<username>} prefix)
   * @param newOriginalFileName new file name (no path separators)
   */
  record BulkUpdateEntry(String fileId, String newDirectoryPath, String newOriginalFileName) {}

  /**
   * Bulk variant of {@link #delete}. Removes N manifests in a single signed roundtrip. If the call
   * fails the caller logs a high-severity WARN and continues.t.
   *
   * <p>Idempotent: missing fileIds do not abort the operation server-side; the response carries
   * {@code (deletedCount, missingCount)}.
   *
   * @param fileIds non-empty list of manifest identifiers; all must belong to this origin
   * @param tutorBaseUrl base URL of the tutor
   * @throws IllegalArgumentException if any argument is null/blank/empty
   * @throws IllegalStateException if the HTTP exchange fails or returns non-2xx
   */
  void deleteBulk(java.util.List<String> fileIds, String tutorBaseUrl);

  /**
   * Cheap reachability probe to the tutor. Issued by the upload pipeline BEFORE distributing
   * fragments to peers to avoid the fail-closed-after-distribution scenario: if the tutor is down,
   * distributing N fragments and then aborting because the manifest cannot be replicated leaves N
   * orphan fragments at peers that only expire by TTL natural.
   *
   * <p>Hits {@code GET /ops/system/health} signed. The endpoint already classifies READY / DEGRADED
   * / NOT_READY (existing observability surface). The preflight treats READY and DEGRADED as "tutor
   * will accept the manifest", NOT_READY (HTTP 503) as "abort upload before distribution".
   *
   * <p>Idempotent and side-effect-free on the tutor. Race condition note: the tutor may go down
   * between this check and the subsequent {@link #store} call; in that case the existing
   * fail-closed still kicks in (with the orphans-at-peers cost we are trying to minimise here, not
   * eliminate).
   *
   * @param tutorBaseUrl base URL of the tutor
   * @throws IllegalArgumentException if {@code tutorBaseUrl} is blank
   * @throws IllegalStateException if the HTTP exchange fails or returns non-2xx
   */
  void checkTutorReachable(String tutorBaseUrl);
}
