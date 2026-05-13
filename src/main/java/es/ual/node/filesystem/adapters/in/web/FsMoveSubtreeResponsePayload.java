package es.ual.node.filesystem.adapters.in.web;

import java.util.List;

/**
 * Response DTO for {@code POST /fs/entries/move-subtree}. Contains the list of entries whose path
 * was rewritten by the operation, in their post-move shape.
 *
 * @param movedEntries entries with their new paths and incremented versions
 */
public record FsMoveSubtreeResponsePayload(List<FsEntryPayload> movedEntries) {}
