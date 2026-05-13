package es.ual.node.userregistration.adapters.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.userregistration.application.AuthenticatedUser;
import es.ual.node.userregistration.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests for {@link UserRoleAuthorizationInterceptor}. */
class UserRoleAuthorizationInterceptorTest {

  private UserRoleAuthorizationInterceptor interceptor;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    interceptor = new UserRoleAuthorizationInterceptor(objectMapper);
  }

  @Test
  void shouldAllowNodeAdminOnOpsEndpoints() throws Exception {
    final MockHttpServletRequest request =
        request("GET", "/ops/discovery/metrics", UserRole.NODE_ADMIN);
    final MockHttpServletResponse response = new MockHttpServletResponse();

    final boolean allowed = interceptor.preHandle(request, response, new Object());

    assertTrue(allowed);
  }

  @Test
  void shouldRejectNodeAdminOnClientEndpointWithStableError() throws Exception {
    final MockHttpServletRequest request = request("GET", "/fs/tree", UserRole.NODE_ADMIN);
    final MockHttpServletResponse response = new MockHttpServletResponse();

    final boolean allowed = interceptor.preHandle(request, response, new Object());

    assertFalse(allowed);
    assertEquals(403, response.getStatus());
    assertEquals("CLIENT_ACCESS_ROLE_FORBIDDEN", errorCode(response));
  }

  @Test
  void shouldAllowEndUserOnClientEndpoint() throws Exception {
    final MockHttpServletRequest request = request("GET", "/sync/changes", UserRole.END_USER);
    final MockHttpServletResponse response = new MockHttpServletResponse();

    final boolean allowed = interceptor.preHandle(request, response, new Object());

    assertTrue(allowed);
  }

  private MockHttpServletRequest request(
      final String method, final String uri, final UserRole role) {
    final MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setAttribute(
        UserSessionAuthenticationInterceptor.AUTHENTICATED_USER_ATTRIBUTE,
        new AuthenticatedUser("tester", 100, role));
    return request;
  }

  private String errorCode(final MockHttpServletResponse response) throws Exception {
    final JsonNode jsonNode = objectMapper.readTree(response.getContentAsString());
    return jsonNode.get("errorCode").asText();
  }
}
