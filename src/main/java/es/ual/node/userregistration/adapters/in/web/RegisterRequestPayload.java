package es.ual.node.userregistration.adapters.in.web;

import es.ual.node.userregistration.application.RegisterUserRequest;

/** Register endpoint payload. */
public record RegisterRequestPayload(String invitationCode, String username, String password) {

  /**
   * Converts payload to use-case request.
   *
   * @return use-case request
   */
  public RegisterUserRequest toApplication() {
    return new RegisterUserRequest(invitationCode, username, password);
  }
}
