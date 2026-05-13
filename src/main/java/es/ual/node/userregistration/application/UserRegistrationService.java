package es.ual.node.userregistration.application;

import es.ual.node.userregistration.domain.RegistrationCode;
import es.ual.node.userregistration.domain.UserAccount;
import es.ual.node.userregistration.domain.UserRole;
import es.ual.node.userregistration.domain.UserSession;
import es.ual.node.userregistration.ports.out.RegistrationCodePort;
import es.ual.node.userregistration.ports.out.UserAccountPort;
import es.ual.node.userregistration.ports.out.UserSessionPort;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.NoSuchElementException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Application service for user bootstrap and registration. */
public class UserRegistrationService {

  private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  private final UserAccountPort userAccountPort;
  private final RegistrationCodePort registrationCodePort;
  private final UserSessionPort userSessionPort;
  private final UserRegistrationProperties properties;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;
  private final SecureRandom secureRandom;

  /**
   * Creates registration service.
   *
   * @param userAccountPort user account persistence
   * @param registrationCodePort registration code persistence
   * @param userSessionPort session persistence
   * @param properties registration properties
   * @param passwordEncoder password encoder
   * @param clock clock
   */
  public UserRegistrationService(
      final UserAccountPort userAccountPort,
      final RegistrationCodePort registrationCodePort,
      final UserSessionPort userSessionPort,
      final UserRegistrationProperties properties,
      final PasswordEncoder passwordEncoder,
      final Clock clock) {
    if (userAccountPort == null
        || registrationCodePort == null
        || userSessionPort == null
        || properties == null
        || passwordEncoder == null
        || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.userAccountPort = userAccountPort;
    this.registrationCodePort = registrationCodePort;
    this.userSessionPort = userSessionPort;
    this.properties = properties;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
    this.secureRandom = new SecureRandom();
  }

  /**
   * Issues a new registration invitation code from node console.
   *
   * @param quotaMb future account quota in MB
   * @return issued code
   */
  public String issueRegistrationCode(final int quotaMb) {
    return issueRegistrationCode(quotaMb, UserRole.END_USER);
  }

  /**
   * Issues a new registration invitation code from node console.
   *
   * @param quotaMb future account quota in MB
   * @param role role assigned to the future account
   * @return issued code
   */
  public String issueRegistrationCode(final int quotaMb, final UserRole role) {
    if (quotaMb <= 0) {
      throw new IllegalArgumentException("quotaMb must be greater than zero");
    }
    if (role == null) {
      throw new IllegalArgumentException("role must not be null");
    }

    final int codeLength = properties.getCodeLength();
    if (codeLength < 6 || codeLength > 8) {
      throw new IllegalArgumentException(
          "node.user-registration.code-length must be between 6 and 8");
    }
    if (properties.getCodeTtlMinutes() <= 0) {
      throw new IllegalArgumentException(
          "node.user-registration.code-ttl-minutes must be greater than zero");
    }

    final Instant now = clock.instant();
    final Instant expiresAt = now.plusSeconds(properties.getCodeTtlMinutes() * 60L);

    for (int attempt = 0; attempt < 10; attempt++) {
      final String code = randomCode(codeLength);
      if (registrationCodePort.findByCode(code).isPresent()) {
        continue;
      }
      registrationCodePort.save(
          new RegistrationCode(code, quotaMb, role, expiresAt, false, null, now));
      return code;
    }

    throw new IllegalStateException("Unable to generate unique registration code");
  }

  /**
   * Registers a user by consuming a valid invitation code.
   *
   * @param request register request
   * @return registered user metadata
   */
  public RegisteredUser register(final RegisterUserRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }

    final String code =
        normalizeNonBlank(request.invitationCode(), "invitationCode").toUpperCase(Locale.ROOT);
    final String username = normalizeUsername(request.username());
    final String password = normalizePassword(request.password());

    if (userAccountPort.existsByUsername(username)) {
      throw new IllegalArgumentException("username is already in use");
    }

    final Instant now = clock.instant();
    final RegistrationCode registrationCode =
        registrationCodePort
            .findByCode(code)
            .orElseThrow(() -> new NoSuchElementException("invitation code not found"));

    if (!registrationCode.isActiveAt(now)) {
      throw new IllegalArgumentException("invitation code is expired or already used");
    }

    final String passwordHash = passwordEncoder.encode(password);
    final UserAccount account =
        new UserAccount(
            username, passwordHash, registrationCode.quotaMb(), registrationCode.role(), now);
    userAccountPort.save(account);
    registrationCodePort.markUsed(code, now);

    return new RegisteredUser(account.username(), account.quotaMb());
  }

  /**
   * Authenticates user with username/password and returns session token.
   *
   * @param request login request
   * @return authenticated session
   */
  public AuthenticatedSession login(final LoginRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }

    final String username = normalizeUsername(request.username());
    final String password = normalizeNonBlank(request.password(), "password");

    final UserAccount account =
        userAccountPort
            .findByUsername(username)
            .orElseThrow(() -> new NoSuchElementException("user not found"));

    if (!passwordEncoder.matches(password, account.passwordHash())) {
      throw new IllegalArgumentException("invalid credentials");
    }

    final long sessionTtlMinutes = properties.getSessionTtlMinutes();
    if (sessionTtlMinutes <= 0) {
      throw new IllegalArgumentException(
          "node.user-registration.session-ttl-minutes must be greater than zero");
    }

    final Instant issuedAt = clock.instant();
    final Instant expiresAt = issuedAt.plusSeconds(sessionTtlMinutes * 60L);
    final String token = randomTokenUnique();
    userSessionPort.save(new UserSession(token, account.username(), issuedAt, expiresAt, false));

    return new AuthenticatedSession(
        token, account.username(), account.quotaMb(), account.role(), expiresAt);
  }

  /**
   * Rotates an active session token and revokes the previous one.
   *
   * @param token current session token
   * @return refreshed authenticated session
   */
  public AuthenticatedSession refresh(final String token) {
    final String normalizedToken = normalizeNonBlank(token, "token");

    final UserSession currentSession =
        userSessionPort
            .findByToken(normalizedToken)
            .orElseThrow(() -> new IllegalArgumentException("session is expired or revoked"));

    final Instant now = clock.instant();
    if (currentSession.revoked() || !currentSession.expiresAt().isAfter(now)) {
      throw new IllegalArgumentException("session is expired or revoked");
    }

    final UserAccount account =
        userAccountPort
            .findByUsername(currentSession.username())
            .orElseThrow(() -> new NoSuchElementException("user not found"));

    final long sessionTtlMinutes = properties.getSessionTtlMinutes();
    if (sessionTtlMinutes <= 0) {
      throw new IllegalArgumentException(
          "node.user-registration.session-ttl-minutes must be greater than zero");
    }

    final Instant refreshedIssuedAt = now;
    final Instant refreshedExpiresAt = refreshedIssuedAt.plusSeconds(sessionTtlMinutes * 60L);
    final String refreshedToken = randomTokenUnique();

    userSessionPort.revoke(normalizedToken);
    userSessionPort.save(
        new UserSession(
            refreshedToken, account.username(), refreshedIssuedAt, refreshedExpiresAt, false));

    return new AuthenticatedSession(
        refreshedToken, account.username(), account.quotaMb(), account.role(), refreshedExpiresAt);
  }

  /**
   * Resolves authenticated user from active session token.
   *
   * @param token session token
   * @return authenticated user
   */
  public AuthenticatedUser authenticateByToken(final String token) {
    final String normalizedToken = normalizeNonBlank(token, "token");
    final UserSession session =
        userSessionPort
            .findByToken(normalizedToken)
            .orElseThrow(() -> new NoSuchElementException("session not found"));

    final Instant now = clock.instant();
    if (session.revoked() || !session.expiresAt().isAfter(now)) {
      throw new IllegalArgumentException("session is expired or revoked");
    }

    final UserAccount account =
        userAccountPort
            .findByUsername(session.username())
            .orElseThrow(() -> new NoSuchElementException("user not found"));

    return new AuthenticatedUser(account.username(), account.quotaMb(), account.role());
  }

  /**
   * Revokes an active session token.
   *
   * @param token session token
   */
  public void logout(final String token) {
    final String normalizedToken = normalizeNonBlank(token, "token");
    userSessionPort.revoke(normalizedToken);
  }

  private String randomCode(final int length) {
    final StringBuilder builder = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      final int index = secureRandom.nextInt(CODE_ALPHABET.length());
      builder.append(CODE_ALPHABET.charAt(index));
    }
    return builder.toString();
  }

  private String randomToken() {
    final byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String randomTokenUnique() {
    for (int attempt = 0; attempt < 10; attempt++) {
      final String token = randomToken();
      if (userSessionPort.findByToken(token).isEmpty()) {
        return token;
      }
    }
    throw new IllegalStateException("Unable to generate unique session token");
  }

  private String normalizeUsername(final String username) {
    final String normalized = normalizeNonBlank(username, "username").trim();
    if (normalized.length() < 3 || normalized.length() > 64) {
      throw new IllegalArgumentException("username length must be between 3 and 64");
    }
    return normalized;
  }

  private String normalizePassword(final String password) {
    final String normalized = normalizeNonBlank(password, "password");
    if (normalized.length() < 8 || normalized.length() > 128) {
      throw new IllegalArgumentException("password length must be between 8 and 128");
    }
    return normalized;
  }

  private String normalizeNonBlank(final String value, final String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value.trim();
  }
}
