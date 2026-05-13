package es.ual.node.reedsolomon.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable manifest describing a full Reed-Solomon fragmentation set. */
public record RsManifest(
    String fileId, String originalHash, RsScheme scheme, List<RsFragment> fragments, long version) {

  /** Creates a validated manifest. */
  public RsManifest {
    if (fileId == null || fileId.isBlank()) {
      throw new IllegalArgumentException("fileId must not be blank");
    }
    if (originalHash == null || originalHash.isBlank()) {
      throw new IllegalArgumentException("originalHash must not be blank");
    }
    if (scheme == null) {
      throw new IllegalArgumentException("scheme must not be null");
    }
    if (fragments == null || fragments.isEmpty()) {
      throw new IllegalArgumentException("fragments must not be empty");
    }
    if (version <= 0) {
      throw new IllegalArgumentException("version must be greater than zero");
    }
    if (fragments.size() != scheme.n()) {
      throw new IllegalArgumentException("fragments size must match scheme n");
    }

    final Map<Integer, String> checksumByIndex = new HashMap<>();
    for (RsFragment fragment : fragments) {
      if (fragment == null) {
        throw new IllegalArgumentException("fragments must not contain null values");
      }
      if (fragment.index() >= scheme.n()) {
        throw new IllegalArgumentException("fragment index out of range for scheme n");
      }
      final String checksum = checksumByIndex.putIfAbsent(fragment.index(), fragment.checksum());
      if (checksum != null) {
        if (!checksum.equals(fragment.checksum())) {
          throw new IllegalArgumentException("duplicate fragment index with different checksum");
        }
        throw new IllegalArgumentException("duplicate fragment index");
      }
    }

    fileId = fileId.trim();
    originalHash = originalHash.trim();
    fragments = List.copyOf(fragments);
  }
}
