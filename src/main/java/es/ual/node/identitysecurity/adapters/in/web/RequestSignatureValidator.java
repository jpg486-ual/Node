package es.ual.node.identitysecurity.adapters.in.web;

import es.ual.node.identitysecurity.application.RequestSignatureValidationService;
import es.ual.node.identitysecurity.application.SignatureValidationException;
import es.ual.node.identitysecurity.application.SignatureValidationRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

/** Spring MVC interceptor that validates node signatures on incoming requests. */
public class RequestSignatureValidator implements HandlerInterceptor {

  /** Header carrying the node id. */
  public static final String HEADER_NODE_ID = "X-Node-Id";

  /** Header carrying the nonce. */
  public static final String HEADER_NONCE = "X-Nonce";

  /** Header carrying the epoch-second timestamp. */
  public static final String HEADER_TIMESTAMP = "X-Timestamp";

  /** Header carrying the base64 signature. */
  public static final String HEADER_SIGNATURE = "X-Signature";

  /** Header carrying signature algorithm name. */
  public static final String HEADER_SIGNATURE_ALGORITHM = "X-Signature-Algorithm";

  private static final Logger LOGGER = LoggerFactory.getLogger(RequestSignatureValidator.class);

  private final RequestSignatureValidationService validationService;
  private final String defaultSignatureAlgorithm;

  /**
   * Creates signature validator interceptor.
   *
   * @param validationService validation application service
   * @param defaultSignatureAlgorithm fallback signature algorithm
   */
  public RequestSignatureValidator(
      final RequestSignatureValidationService validationService,
      final String defaultSignatureAlgorithm) {
    if (validationService == null) {
      throw new IllegalArgumentException("validationService must not be null");
    }
    if (defaultSignatureAlgorithm == null || defaultSignatureAlgorithm.isBlank()) {
      throw new IllegalArgumentException("defaultSignatureAlgorithm must not be blank");
    }
    this.validationService = validationService;
    this.defaultSignatureAlgorithm = defaultSignatureAlgorithm.trim();
  }

  /**
   * Validates security headers and signature before dispatch.
   *
   * @param request current HTTP request
   * @param response current HTTP response
   * @param handler chosen handler
   * @return {@code true} when request is authorized
   * @throws Exception if response error writing fails
   */
  @Override
  public boolean preHandle(
      @NonNull final HttpServletRequest request,
      @NonNull final HttpServletResponse response,
      @NonNull final Object handler)
      throws Exception {
    final String nodeId = request.getHeader(HEADER_NODE_ID);
    final String nonce = request.getHeader(HEADER_NONCE);
    final String timestampHeader = request.getHeader(HEADER_TIMESTAMP);
    final String signature = request.getHeader(HEADER_SIGNATURE);
    final String algorithmHeader = request.getHeader(HEADER_SIGNATURE_ALGORITHM);
    final String algorithm =
        isBlank(algorithmHeader) ? defaultSignatureAlgorithm : algorithmHeader.trim();

    if (isBlank(nodeId) || isBlank(nonce) || isBlank(timestampHeader) || isBlank(signature)) {
      LOGGER
          .atWarn()
          .setMessage("Missing required signature headers")
          .addKeyValue("path", request.getRequestURI())
          .addKeyValue("method", request.getMethod())
          .log();
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing signature headers");
      return false;
    }

    final long timestamp;
    try {
      timestamp = Long.parseLong(timestampHeader);
    } catch (NumberFormatException ex) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid signature timestamp");
      return false;
    }

    try {
      validationService.validate(
          new SignatureValidationRequest(
              nodeId,
              nonce,
              timestamp,
              signature,
              algorithm,
              request.getMethod(),
              request.getRequestURI(),
              request.getQueryString()));
      return true;
    } catch (SignatureValidationException ex) {
      LOGGER
          .atWarn()
          .setMessage("Request authentication failed")
          .addKeyValue("nodeId", nodeId)
          .addKeyValue("path", request.getRequestURI())
          .addKeyValue("reason", ex.getMessage())
          .log();
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED, ex.getMessage());
      return false;
    }
  }

  private boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
