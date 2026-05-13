package es.ual.node.filesystem.adapters.in.web;

/**
 * Inbound DTO for {@code POST /fs/entries/move-subtree}. Carries the bulk MOVE/RENAME of a sub-tree
 * rooted at {@code fromPath} to {@code toPath}.
 *
 * <p>The node lists all entries whose {@code path} matches {@code fromPath} or {@code fromPath/...}
 * and applies the relocation locally + replicates path changes for affected FILE manifests to the
 * tutor in a single bulk roundtrip. Atomicidad estricta: o todo o nada.
 *
 * @param fromPath source path (the root of the sub-tree to move)
 * @param toPath destination path (the sub-tree's new root)
 */
public record FsMoveSubtreeRequestPayload(String fromPath, String toPath) {}
