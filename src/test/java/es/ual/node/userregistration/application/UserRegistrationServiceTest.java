package es.ual.node.userregistration.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.userregistration.adapters.out.memory.InMemoryRegistrationCodePort;
import es.ual.node.userregistration.adapters.out.memory.InMemoryUserAccountPort;
import es.ual.node.userregistration.adapters.out.memory.InMemoryUserSessionPort;
import es.ual.node.userregistration.domain.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Unit tests for user registration and login flows. */
class UserRegistrationServiceTest {

  @Test
  void registerThenLoginReturnsSessionToken() {
    UserRegistrationProperties properties = new UserRegistrationProperties();
    properties.setCodeLength(8);
    properties.setCodeTtlMinutes(30);
    properties.setSessionTtlMinutes(120);

    Clock clock = Clock.fixed(Instant.parse("2026-03-07T10:00:00Z"), ZoneOffset.UTC);
    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    UserRegistrationService service =
        new UserRegistrationService(
            new InMemoryUserAccountPort(),
            new InMemoryRegistrationCodePort(),
            new InMemoryUserSessionPort(),
            properties,
            passwordEncoder,
            clock);

    String invitationCode = service.issueRegistrationCode(2048);
    RegisteredUser registered =
        service.register(new RegisterUserRequest(invitationCode, "alice", "Passw0rd!"));

    assertEquals("alice", registered.username());
    assertEquals(2048, registered.quotaMb());

    AuthenticatedSession session = service.login(new LoginRequest("alice", "Passw0rd!"));
    assertNotNull(session.token());
    assertTrue(session.token().length() >= 20);
    assertEquals("alice", session.username());
    assertEquals(2048, session.quotaMb());
    assertEquals(UserRole.END_USER, session.role());
    assertEquals(Instant.parse("2026-03-07T12:00:00Z"), session.expiresAt());
  }

  @Test
  void loginWithInvalidPasswordFails() {
    UserRegistrationProperties properties = new UserRegistrationProperties();
    properties.setCodeLength(8);
    properties.setCodeTtlMinutes(30);
    properties.setSessionTtlMinutes(120);

    Clock clock = Clock.fixed(Instant.parse("2026-03-07T10:00:00Z"), ZoneOffset.UTC);
    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    UserRegistrationService service =
        new UserRegistrationService(
            new InMemoryUserAccountPort(),
            new InMemoryRegistrationCodePort(),
            new InMemoryUserSessionPort(),
            properties,
            passwordEncoder,
            clock);

    String invitationCode = service.issueRegistrationCode(1024);
    service.register(new RegisterUserRequest(invitationCode, "bob", "Passw0rd!"));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.login(new LoginRequest("bob", "wrong-password")));
  }

  @Test
  void authByTokenThenLogoutInvalidatesSession() {
    UserRegistrationProperties properties = new UserRegistrationProperties();
    properties.setCodeLength(8);
    properties.setCodeTtlMinutes(30);
    properties.setSessionTtlMinutes(120);

    Clock clock = Clock.fixed(Instant.parse("2026-03-07T10:00:00Z"), ZoneOffset.UTC);
    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    UserRegistrationService service =
        new UserRegistrationService(
            new InMemoryUserAccountPort(),
            new InMemoryRegistrationCodePort(),
            new InMemoryUserSessionPort(),
            properties,
            passwordEncoder,
            clock);

    String invitationCode = service.issueRegistrationCode(512);
    service.register(new RegisterUserRequest(invitationCode, "carol", "Passw0rd!"));
    AuthenticatedSession session = service.login(new LoginRequest("carol", "Passw0rd!"));

    AuthenticatedUser me = service.authenticateByToken(session.token());
    assertEquals("carol", me.username());
    assertEquals(512, me.quotaMb());
    assertEquals(UserRole.END_USER, me.role());

    service.logout(session.token());

    assertThrows(
        IllegalArgumentException.class, () -> service.authenticateByToken(session.token()));
  }

  @Test
  void refreshRotatesTokenAndRevokesPreviousSession() {
    UserRegistrationProperties properties = new UserRegistrationProperties();
    properties.setCodeLength(8);
    properties.setCodeTtlMinutes(30);
    properties.setSessionTtlMinutes(120);

    Clock clock = Clock.fixed(Instant.parse("2026-03-07T10:00:00Z"), ZoneOffset.UTC);
    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    UserRegistrationService service =
        new UserRegistrationService(
            new InMemoryUserAccountPort(),
            new InMemoryRegistrationCodePort(),
            new InMemoryUserSessionPort(),
            properties,
            passwordEncoder,
            clock);

    String invitationCode = service.issueRegistrationCode(1024);
    service.register(new RegisterUserRequest(invitationCode, "dave", "Passw0rd!"));
    AuthenticatedSession session = service.login(new LoginRequest("dave", "Passw0rd!"));

    AuthenticatedSession refreshed = service.refresh(session.token());
    assertNotEquals(session.token(), refreshed.token());
    assertEquals("dave", refreshed.username());
    assertEquals(1024, refreshed.quotaMb());
    assertEquals(UserRole.END_USER, refreshed.role());

    assertThrows(
        IllegalArgumentException.class, () -> service.authenticateByToken(session.token()));

    AuthenticatedUser me = service.authenticateByToken(refreshed.token());
    assertEquals("dave", me.username());
    assertEquals(1024, me.quotaMb());
    assertEquals(UserRole.END_USER, me.role());
  }

  @Test
  void adminInvitationCodeCreatesAdminSessionRole() {
    UserRegistrationProperties properties = new UserRegistrationProperties();
    properties.setCodeLength(8);
    properties.setCodeTtlMinutes(30);
    properties.setSessionTtlMinutes(120);

    Clock clock = Clock.fixed(Instant.parse("2026-03-07T10:00:00Z"), ZoneOffset.UTC);
    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    UserRegistrationService service =
        new UserRegistrationService(
            new InMemoryUserAccountPort(),
            new InMemoryRegistrationCodePort(),
            new InMemoryUserSessionPort(),
            properties,
            passwordEncoder,
            clock);

    String invitationCode = service.issueRegistrationCode(2048, UserRole.NODE_ADMIN);
    service.register(new RegisterUserRequest(invitationCode, "node-admin", "Passw0rd!"));

    AuthenticatedSession session = service.login(new LoginRequest("node-admin", "Passw0rd!"));
    assertEquals(UserRole.NODE_ADMIN, session.role());
  }
}
