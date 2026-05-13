package es.ual.node.userregistration.adapters.in.web;

import es.ual.node.userregistration.application.AuthenticatedSession;
import es.ual.node.userregistration.application.AuthenticatedUser;
import es.ual.node.userregistration.application.RegisteredUser;
import es.ual.node.userregistration.application.UserRegistrationService;
import es.ual.node.userregistration.ports.out.UserQuotaPort;
import java.util.NoSuchElementException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Client-facing authentication endpoints. */
@RestController
@RequestMapping("/auth")
public class AuthController {

  private final UserRegistrationService userRegistrationService;
  private final UserQuotaPort userQuotaPort;

  /**
   * Creates auth controller.
   *
   * @param userRegistrationService registration service
   * @param userQuotaPort live quota consumption tracker. Always present. The bean is wired by
   *     either the in-memory or Postgres persistence configuration.
   */
  public AuthController(
      final UserRegistrationService userRegistrationService, final UserQuotaPort userQuotaPort) {
    if (userRegistrationService == null || userQuotaPort == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.userRegistrationService = userRegistrationService;
    this.userQuotaPort = userQuotaPort;
  }

  /**
   * Registers user by consuming invitation code.
   *
   * @param payload register payload
   * @return registered user payload
   */
  @PostMapping("/register")
  public ResponseEntity<?> register(@RequestBody final RegisterRequestPayload payload) {
    try {
      final RegisteredUser registeredUser =
          userRegistrationService.register(payload.toApplication());
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(RegisterResponsePayload.fromApplication(registeredUser));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(ApiErrorPayload.of("INVITATION_CODE_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("REGISTER_VALIDATION_ERROR", ex.getMessage()));
    }
  }

  /**
   * Authenticates user and returns session token.
   *
   * @param payload login payload
   * @return login response payload
   */
  @PostMapping("/login")
  public ResponseEntity<?> login(@RequestBody final LoginRequestPayload payload) {
    try {
      final AuthenticatedSession session = userRegistrationService.login(payload.toApplication());
      return ResponseEntity.ok(LoginResponsePayload.fromApplication(session));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(ApiErrorPayload.of("USER_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ApiErrorPayload.of("INVALID_CREDENTIALS", ex.getMessage()));
    }
  }

  /**
   * Returns authenticated user profile from session token.
   *
   * @param authorizationHeader authorization header
   * @return authenticated user payload
   */
  @GetMapping("/me")
  public ResponseEntity<?> me(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
          final String authorizationHeader) {
    try {
      final String token = extractBearerToken(authorizationHeader);
      final AuthenticatedUser user = userRegistrationService.authenticateByToken(token);
      final long quotaUsedBytes = userQuotaPort.usedBytes(user.username());
      return ResponseEntity.ok(AuthenticatedUserPayload.fromApplication(user, quotaUsedBytes));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(ApiErrorPayload.of("USER_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ApiErrorPayload.of("INVALID_SESSION", ex.getMessage()));
    }
  }

  /**
   * Revokes current session token.
   *
   * @param authorizationHeader authorization header
   * @return empty response
   */
  @PostMapping("/logout")
  public ResponseEntity<?> logout(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
          final String authorizationHeader) {
    try {
      final String token = extractBearerToken(authorizationHeader);
      userRegistrationService.logout(token);
      return ResponseEntity.noContent().build();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ApiErrorPayload.of("INVALID_SESSION", ex.getMessage()));
    }
  }

  /**
   * Rotates an active session token and revokes the previous one.
   *
   * @param authorizationHeader authorization header
   * @return refreshed session payload
   */
  @PostMapping("/refresh")
  public ResponseEntity<?> refresh(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
          final String authorizationHeader) {
    try {
      final String token = extractBearerToken(authorizationHeader);
      final AuthenticatedSession refreshedSession = userRegistrationService.refresh(token);
      return ResponseEntity.ok(LoginResponsePayload.fromApplication(refreshedSession));
    } catch (NoSuchElementException ex) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(ApiErrorPayload.of("USER_NOT_FOUND", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ApiErrorPayload.of("INVALID_SESSION", ex.getMessage()));
    }
  }

  private String extractBearerToken(final String authorizationHeader) {
    if (authorizationHeader == null || authorizationHeader.isBlank()) {
      throw new IllegalArgumentException("Authorization header is required");
    }
    final String prefix = "Bearer ";
    if (!authorizationHeader.startsWith(prefix)
        || authorizationHeader.length() <= prefix.length()) {
      throw new IllegalArgumentException("Authorization must be a Bearer token");
    }
    return authorizationHeader.substring(prefix.length()).trim();
  }
}
