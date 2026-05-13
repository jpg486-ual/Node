package es.ual.node.userregistration.adapters.in.web;

import es.ual.node.userregistration.application.RegisteredUser;

/** Register endpoint response payload. */
public record RegisterResponsePayload(String username, int quotaMb) {

  /**
   * Maps use-case output to payload.
   *
   * @param registeredUser output model
   * @return payload
   */
  public static RegisterResponsePayload fromApplication(final RegisteredUser registeredUser) {
    return new RegisterResponsePayload(registeredUser.username(), registeredUser.quotaMb());
  }
}
