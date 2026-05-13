package es.ual.node.filesystem.adapters.in.web;

/**
 * Inbound DTO for {@code POST /fs/entries/delete-subtree}. Carries the bulk DELETE of a sub-tree
 * rooted at {@code path}.
 *
 * <p>The node lists every active entry whose {@code path} matches {@code path} or {@code path/...}
 * and converts them to tombstones in a single transaction; the affected FILE manifests are removed
 * from the tutor in a single best-effort roundtrip. If the tutor is unreachable, the local
 * tombstones persist and the renewal worker's cross-check compensate (no rollback needed).
 *
 * @param path sub-tree root (the element and every descendant become tombstones)
 */
public record FsDeleteSubtreeRequestPayload(String path) {}
