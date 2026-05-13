package es.ual.node.sync.adapters.in.web;

import es.ual.node.filesystem.adapters.in.web.FsEntryPayload;
import java.time.Instant;
import java.util.List;

/** Sync changes response payload. */
public record SyncChangesPayload(
    String username, long cursor, Instant snapshotAt, List<FsEntryPayload> changes) {}
