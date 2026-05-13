package es.ual.node.userregistration.application;

import es.ual.node.userregistration.domain.UserRole;

/** Authenticated user profile resolved from session token. */
public record AuthenticatedUser(String username, int quotaMb, UserRole role) {}
