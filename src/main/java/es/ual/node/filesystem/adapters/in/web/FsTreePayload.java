package es.ual.node.filesystem.adapters.in.web;

import java.time.Instant;
import java.util.List;

/** Filesystem tree snapshot payload. */
public record FsTreePayload(
    String username, long cursor, Instant snapshotAt, List<FsEntryPayload> entries) {}
