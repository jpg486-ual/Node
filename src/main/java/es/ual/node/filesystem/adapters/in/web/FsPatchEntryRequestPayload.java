package es.ual.node.filesystem.adapters.in.web;

import es.ual.node.filesystem.application.FsPatchRequest;

/** Patch filesystem entry payload. */
public record FsPatchEntryRequestPayload(String newPath) {

  /**
   * Maps payload to patch request.
   *
   * @param username authenticated username
   * @param entryId entry id
   * @return patch request
   */
  public FsPatchRequest toApplication(final String username, final String entryId) {
    return new FsPatchRequest(username, entryId, newPath);
  }
}
