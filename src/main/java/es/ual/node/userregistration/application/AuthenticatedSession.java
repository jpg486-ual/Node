package es.ual.node.userregistration.application;

import es.ual.node.userregistration.domain.UserRole;
import java.time.Instant;

/** Login use-case output model. */
public record AuthenticatedSession(
    String token, String username, int quotaMb, UserRole role, Instant expiresAt) {}
