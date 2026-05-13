package es.ual.node.sync.application;

import java.time.Instant;

/** Event payload sent through sync SSE channel. */
public record SyncEventPayload(String type, Long cursor, String entryId, Instant occurredAt) {}
