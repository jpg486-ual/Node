package es.ual.node.filesystem.application;

/** Patch filesystem entry request. */
public record FsPatchRequest(String username, String entryId, String newPath) {}
