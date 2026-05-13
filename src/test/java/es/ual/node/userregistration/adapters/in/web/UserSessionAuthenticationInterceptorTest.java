package es.ual.node.userregistration.adapters.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import es.ual.node.bootstrap.observability.RequestCorrelationInterceptor;
import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.userregistration.application.AuthenticatedUser;
import es.ual.node.userregistration.application.UserRegistrationService;
import es.ual.node.userregistration.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests for {@link UserSessionAuthenticationInterceptor}. */
@ExtendWith(MockitoExtension.class)
class UserSessionAuthenticationInterceptorTest {

  @Mock private UserRegistrationService userRegistrationService;

  private UserSessionAuthenticationInterceptor interceptor;

  @BeforeEach
  void setUp() {
    interceptor = new UserSessionAuthenticationInterceptor(userRegistrationService);
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  @Test
  void shouldAuthenticateAndPopulateSessionCorrelationMdc() throws Exception {
    AuthenticatedUser authenticatedUser = new AuthenticatedUser("admin", 1024, UserRole.NODE_ADMIN);
    when(userRegistrationService.authenticateByToken("token-123")).thenReturn(authenticatedUser);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ops/discovery/metrics");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token-123");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertTrue(allowed);
    assertEquals(
        authenticatedUser,
        request.getAttribute(UserSessionAuthenticationInterceptor.AUTHENTICATED_USER_ATTRIBUTE));
    assertNotNull(MDC.get(RequestCorrelationInterceptor.MDC_SESSION_ID_KEY));
    assertEquals("admin", MDC.get(RequestCorrelationInterceptor.MDC_USERNAME_KEY));
    assertEquals("NODE_ADMIN", MDC.get(RequestCorrelationInterceptor.MDC_USER_ROLE_KEY));

    interceptor.afterCompletion(request, response, new Object(), null);

    assertNull(MDC.get(RequestCorrelationInterceptor.MDC_SESSION_ID_KEY));
    assertNull(MDC.get(RequestCorrelationInterceptor.MDC_USERNAME_KEY));
    assertNull(MDC.get(RequestCorrelationInterceptor.MDC_USER_ROLE_KEY));
  }

  @Test
  void shouldRejectInvalidSessionAndReturnUnauthorized() throws Exception {
    when(userRegistrationService.authenticateByToken("expired-token"))
        .thenThrow(new IllegalArgumentException("session expired"));

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/files");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer expired-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertFalse(allowed);
    assertEquals(401, response.getStatus());
    assertNull(MDC.get(RequestCorrelationInterceptor.MDC_SESSION_ID_KEY));
    assertNull(MDC.get(RequestCorrelationInterceptor.MDC_USERNAME_KEY));
    assertNull(MDC.get(RequestCorrelationInterceptor.MDC_USER_ROLE_KEY));
  }

  @Test
  void shouldRejectWhenAuthorizationHeaderIsMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sync/status");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertFalse(allowed);
    assertEquals(401, response.getStatus());
    verifyNoInteractions(userRegistrationService);
  }

  @Test
  void shouldAllowSignedOpsRequestWithoutBearerAndPopulateSyntheticContext() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/ops/custody-liveness/remote/node1/probe-now");
    request.addHeader(RequestSignatureValidator.HEADER_NODE_ID, "node-abc123");
    request.addHeader(RequestSignatureValidator.HEADER_NONCE, "nonce-1");
    request.addHeader(RequestSignatureValidator.HEADER_TIMESTAMP, "1713550000");
    request.addHeader(RequestSignatureValidator.HEADER_SIGNATURE, "base64-signature");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertTrue(allowed);
    assertEquals(200, response.getStatus());
    AuthenticatedUser authenticatedUser =
        (AuthenticatedUser)
            request.getAttribute(UserSessionAuthenticationInterceptor.AUTHENTICATED_USER_ATTRIBUTE);
    assertNotNull(authenticatedUser);
    assertEquals("signed-node:node-abc123", authenticatedUser.username());
    assertEquals(UserRole.SUPERNODE_ADMIN, authenticatedUser.role());
    assertNotNull(MDC.get(RequestCorrelationInterceptor.MDC_SESSION_ID_KEY));
    assertEquals(
        "signed-node:node-abc123", MDC.get(RequestCorrelationInterceptor.MDC_USERNAME_KEY));
    assertEquals("SUPERNODE_ADMIN", MDC.get(RequestCorrelationInterceptor.MDC_USER_ROLE_KEY));
    verifyNoInteractions(userRegistrationService);
  }

  @Test
  void shouldRejectOpsWhenMissingBearerAndSignatureHeaders() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ops/discovery/metrics");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertFalse(allowed);
    assertEquals(401, response.getStatus());
    verifyNoInteractions(userRegistrationService);
  }
}
