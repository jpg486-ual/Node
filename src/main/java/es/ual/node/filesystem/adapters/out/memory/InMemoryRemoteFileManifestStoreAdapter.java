package es.ual.node.filesystem.adapters.out.memory;

import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort;
import es.ual.node.negotiation.domain.FileManifest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory test adapter for {@link RemoteFileManifestStorePort}. Records replications keyed by
 * {@code (tutorBaseUrl, fileId)} so tests can assert that the origin replicated the manifest with
 * the expected placements. Does not perform HTTP I/O.
 */
public class InMemoryRemoteFileManifestStoreAdapter implements RemoteFileManifestStorePort {

  private final Map<String, ReplicationRecord> records = new ConcurrentHashMap<>();
  private final Map<String, PathUpdateRecord> pathUpdates = new ConcurrentHashMap<>();
  private final java.util.List<BulkUpdateRecord> bulkUpdates = new java.util.ArrayList<>();
  private final java.util.List<BulkDeleteRecord> bulkDeletes = new java.util.ArrayList<>();
  private boolean updatePathFails = false;
  private boolean updatePathBulkFails = false;
  private boolean deleteBulkFails = false;
  private boolean tutorHealthCheckFails = false;
  private int tutorHealthCheckInvocationCount = 0;

  @Override
  public void store(
      final FileManifest manifest,
      final List<FragmentPlacement> placements,
      final String tutorBaseUrl) {
    if (manifest == null) {
      throw new IllegalArgumentException("manifest must not be null");
    }
    if (placements == null) {
      throw new IllegalArgumentException("placements must not be null");
    }
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }
    final String key = key(tutorBaseUrl, manifest.fileId());
    records.put(key, new ReplicationRecord(manifest, List.copyOf(placements)));
  }

  @Override
  public void delete(final String fileId, final String tutorBaseUrl) {
    if (fileId == null || fileId.isBlank()) {
      throw new IllegalArgumentException("fileId must not be blank");
    }
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }
    records.remove(key(tutorBaseUrl, fileId));
  }

  @Override
  public void checkTutorReachable(final String tutorBaseUrl) {
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }
    tutorHealthCheckInvocationCount++;
    if (tutorHealthCheckFails) {
      throw new IllegalStateException("simulated tutor health check failure");
    }
  }

  /**
   * Test hook: subsequent {@link #checkTutorReachable} calls will throw to simulate a tutor that
   * does not respond to {@code GET /ops/system/health}.
   */
  public void simulateHealthCheckFailure(final boolean shouldFail) {
    this.tutorHealthCheckFails = shouldFail;
  }

  /** Returns total number of health-check invocations recorded. */
  public int healthCheckInvocationCount() {
    return tutorHealthCheckInvocationCount;
  }

  @Override
  public void deleteBulk(final List<String> fileIds, final String tutorBaseUrl) {
    if (fileIds == null || fileIds.isEmpty()) {
      throw new IllegalArgumentException("fileIds must not be empty");
    }
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }
    if (deleteBulkFails) {
      throw new IllegalStateException("simulated tutor unreachable (bulk delete)");
    }
    bulkDeletes.add(new BulkDeleteRecord(tutorBaseUrl.trim(), List.copyOf(fileIds)));
    for (String f : fileIds) {
      records.remove(key(tutorBaseUrl, f));
      pathUpdates.remove(key(tutorBaseUrl, f));
    }
  }

  /** Test hook: subsequent {@link #deleteBulk} calls will throw to simulate tutor unreachable. */
  public void simulateBulkDeleteFailure(final boolean shouldFail) {
    this.deleteBulkFails = shouldFail;
  }

  /** Returns recorded bulk-delete invocations against the given tutor (in invocation order). */
  public java.util.List<BulkDeleteRecord> findBulkDeletes(final String tutorBaseUrl) {
    return bulkDeletes.stream().filter(r -> r.tutorBaseUrl().equals(tutorBaseUrl.trim())).toList();
  }

  /** Returns total number of bulk-delete invocations recorded. */
  public int bulkDeleteInvocationCount() {
    return bulkDeletes.size();
  }

  @Override
  public void updatePathBulk(final List<BulkUpdateEntry> entries, final String tutorBaseUrl) {
    if (entries == null || entries.isEmpty()) {
      throw new IllegalArgumentException("entries must not be empty");
    }
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }
    if (updatePathBulkFails) {
      throw new IllegalStateException("simulated tutor unreachable (bulk)");
    }
    bulkUpdates.add(new BulkUpdateRecord(tutorBaseUrl.trim(), List.copyOf(entries)));
    // Tambien indexamos por (tutorBaseUrl, fileId) en pathUpdates para que los tests que ya usan
    // findPathUpdate sigan funcionando con el bulk.
    for (BulkUpdateEntry e : entries) {
      pathUpdates.put(
          key(tutorBaseUrl, e.fileId()),
          new PathUpdateRecord(e.fileId(), e.newDirectoryPath(), e.newOriginalFileName()));
    }
  }

  /**
   * Test hook: subsequent {@link #updatePathBulk} calls will throw to simulate tutor unreachable.
   */
  public void simulateUpdatePathBulkFailure(final boolean shouldFail) {
    this.updatePathBulkFails = shouldFail;
  }

  /** Returns the recorded bulk updates against the given tutor (in invocation order). */
  public java.util.List<BulkUpdateRecord> findBulkUpdates(final String tutorBaseUrl) {
    return bulkUpdates.stream().filter(r -> r.tutorBaseUrl().equals(tutorBaseUrl.trim())).toList();
  }

  /** Returns total number of bulk-update invocations recorded. */
  public int bulkUpdateInvocationCount() {
    return bulkUpdates.size();
  }

  @Override
  public void updatePath(
      final String fileId,
      final String newDirectoryPath,
      final String newOriginalFileName,
      final String tutorBaseUrl) {
    if (fileId == null || fileId.isBlank()) {
      throw new IllegalArgumentException("fileId must not be blank");
    }
    if (newDirectoryPath == null || newDirectoryPath.isBlank()) {
      throw new IllegalArgumentException("newDirectoryPath must not be blank");
    }
    if (newOriginalFileName == null || newOriginalFileName.isBlank()) {
      throw new IllegalArgumentException("newOriginalFileName must not be blank");
    }
    if (tutorBaseUrl == null || tutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("tutorBaseUrl must not be blank");
    }
    if (updatePathFails) {
      throw new IllegalStateException("simulated tutor unreachable");
    }
    pathUpdates.put(
        key(tutorBaseUrl, fileId),
        new PathUpdateRecord(fileId, newDirectoryPath, newOriginalFileName));
  }

  /** Test hook: subsequent {@link #updatePath} calls will throw to simulate tutor unreachable. */
  public void simulateUpdatePathFailure(final boolean shouldFail) {
    this.updatePathFails = shouldFail;
  }

  /** Returns the recorded path update for the given tutor and fileId, if any. */
  public PathUpdateRecord findPathUpdate(final String tutorBaseUrl, final String fileId) {
    return pathUpdates.get(key(tutorBaseUrl, fileId));
  }

  /** Returns the total number of path updates currently held. */
  public int pathUpdateCount() {
    return pathUpdates.size();
  }

  /** Returns the recorded replication for the given tutor and fileId, if any. */
  public ReplicationRecord findRecord(final String tutorBaseUrl, final String fileId) {
    return records.get(key(tutorBaseUrl, fileId));
  }

  /** Returns the total number of replications currently held. */
  public int recordCount() {
    return records.size();
  }

  /** Clears all recorded replications. */
  public void clear() {
    records.clear();
  }

  private static String key(final String tutorBaseUrl, final String fileId) {
    return tutorBaseUrl.trim() + "|" + fileId.trim();
  }

  /** Captured replication for assertion purposes. */
  public record ReplicationRecord(FileManifest manifest, List<FragmentPlacement> placements) {}

  /** Captured path update for assertion purposes. */
  public record PathUpdateRecord(
      String fileId, String newDirectoryPath, String newOriginalFileName) {}

  /** Captured bulk update invocation for assertion purposes. */
  public record BulkUpdateRecord(String tutorBaseUrl, List<BulkUpdateEntry> entries) {}

  /** Captured bulk delete invocation for assertion purposes. */
  public record BulkDeleteRecord(String tutorBaseUrl, List<String> fileIds) {}
}
