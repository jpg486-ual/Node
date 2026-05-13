package es.ual.node.filesystem.adapters.in.web;

import java.util.List;

/**
 * Response DTO for {@code POST /fs/entries/delete-subtree}. Contains the list of entries that
 * became tombstones, in their post-delete shape (deleted=true, fileId=null, path mangled to {@code
 * /__deleted__/<entryId>/<originalPath>}).
 *
 * @param deletedEntries tombstones in leaf-first order
 */
public record FsDeleteSubtreeResponsePayload(List<FsEntryPayload> deletedEntries) {}
