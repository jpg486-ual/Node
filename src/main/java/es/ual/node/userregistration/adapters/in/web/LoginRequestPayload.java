package es.ual.node.userregistration.adapters.in.web;

import es.ual.node.userregistration.application.LoginRequest;

/** Login endpoint payload. */
public record LoginRequestPayload(String username, String password) {

  /**
   * Maps payload to application model.
   *
   * @return login request
   */
  public LoginRequest toApplication() {
    return new LoginRequest(username, password);
  }
}
