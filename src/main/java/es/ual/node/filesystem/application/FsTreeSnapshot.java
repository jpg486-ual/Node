package es.ual.node.filesystem.application;

import es.ual.node.filesystem.domain.FsEntry;
import java.time.Instant;
import java.util.List;

/** Filesystem tree snapshot for user. */
public record FsTreeSnapshot(
    String username, Instant snapshotAt, long cursor, List<FsEntry> entries) {}
