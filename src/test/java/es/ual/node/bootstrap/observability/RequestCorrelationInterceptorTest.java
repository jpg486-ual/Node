package es.ual.node.bootstrap.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests for {@link RequestCorrelationInterceptor}. */
class RequestCorrelationInterceptorTest {

  private final RequestCorrelationInterceptor interceptor = new RequestCorrelationInterceptor();

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  @Test
  void shouldUseProvidedHeaderAndPopulateMdc() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ops/discovery/metrics");
    request.addHeader(RequestCorrelationInterceptor.HEADER_REQUEST_ID, "req_123-ABC");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertTrue(allowed);
    assertEquals(
        "req_123-ABC", request.getAttribute(RequestCorrelationInterceptor.REQUEST_ID_ATTRIBUTE));
    assertEquals(
        "req_123-ABC", response.getHeader(RequestCorrelationInterceptor.HEADER_REQUEST_ID));
    assertEquals("req_123-ABC", MDC.get(RequestCorrelationInterceptor.MDC_REQUEST_ID_KEY));
    assertEquals("GET", MDC.get(RequestCorrelationInterceptor.MDC_HTTP_METHOD_KEY));
    assertEquals(
        "/ops/discovery/metrics", MDC.get(RequestCorrelationInterceptor.MDC_HTTP_PATH_KEY));

    interceptor.afterCompletion(request, response, new Object(), null);

    assertNull(MDC.get(RequestCorrelationInterceptor.MDC_REQUEST_ID_KEY));
    assertNull(MDC.get(RequestCorrelationInterceptor.MDC_HTTP_METHOD_KEY));
    assertNull(MDC.get(RequestCorrelationInterceptor.MDC_HTTP_PATH_KEY));
  }

  @Test
  void shouldGenerateRequestIdWhenHeaderIsMissing() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/custody/liveness/probes");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertTrue(allowed);
    Object requestId = request.getAttribute(RequestCorrelationInterceptor.REQUEST_ID_ATTRIBUTE);
    assertNotNull(requestId);
    assertFalse(requestId.toString().isBlank());
    assertEquals(
        requestId.toString(), response.getHeader(RequestCorrelationInterceptor.HEADER_REQUEST_ID));
  }

  @Test
  void resolveRequestIdShouldPreferAttributeOverHeader() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/files");
    request.setAttribute(RequestCorrelationInterceptor.REQUEST_ID_ATTRIBUTE, "attr-req-1");
    request.addHeader(RequestCorrelationInterceptor.HEADER_REQUEST_ID, "header-req-2");

    String resolved = RequestCorrelationInterceptor.resolveRequestId(request);

    assertEquals("attr-req-1", resolved);
  }
}
