package es.ual.node.userregistration.application;

/** Register-user use case input model. */
public record RegisterUserRequest(String invitationCode, String username, String password) {}
