package es.ual.node.discovery.adapters.out.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.discovery.application.DiscoveryUnreachableException;
import es.ual.node.discovery.domain.DiscoveryRequest;
import es.ual.node.discovery.domain.DiscoveryResponse;
import es.ual.node.discovery.ports.out.RemoteDiscoveryQueryClientPort;
import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.identitysecurity.application.RequestSignatureValidationService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signed HTTP adapter that POSTs a {@link DiscoveryRequest} to a remote supernode at {@code
 * /ops/discovery/query} and unmarshals the response into a {@link DiscoveryResponse}. Used by nodos
 * comunes en el upload pipeline para resolver custodios sin mantener una réplica local del
 * directorio.
 *
 * <p>Mismo patrón canónico de firma que {@link SignedHttpRemoteDiscoveryCandidateClient}: canonical
 * {@code (POST, /ops/discovery/query, null, nonce, timestamp)}, headers {@code X-Node-Id / X-Nonce
 * / X-Timestamp / X-Signature-Algorithm / X-Signature}, ECDSA SHA256.
 */
public class SignedHttpRemoteDiscoveryQueryClient implements RemoteDiscoveryQueryClientPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SignedHttpRemoteDiscoveryQueryClient.class);
  private static final String PATH = "/ops/discovery/query";
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final NodeIdentityContext nodeIdentityContext;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String signatureAlgorithm;

  /** Creates client. */
  public SignedHttpRemoteDiscoveryQueryClient(
      final NodeIdentityContext nodeIdentityContext,
      final ObjectMapper objectMapper,
      final String signatureAlgorithm) {
    if (nodeIdentityContext == null || objectMapper == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    if (signatureAlgorithm == null || signatureAlgorithm.isBlank()) {
      throw new IllegalArgumentException("signatureAlgorithm must not be blank");
    }
    this.nodeIdentityContext = nodeIdentityContext;
    this.objectMapper = objectMapper;
    this.signatureAlgorithm = signatureAlgorithm.trim();
    this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @Override
  public DiscoveryResponse discover(final String supernodeBaseUrl, final DiscoveryRequest request) {
    if (supernodeBaseUrl == null || supernodeBaseUrl.isBlank()) {
      throw new IllegalArgumentException("supernodeBaseUrl must not be blank");
    }
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }

    final URI uri = URI.create(normalizeBaseUrl(supernodeBaseUrl) + PATH);
    final String nonce = "discovery-query-" + UUID.randomUUID();
    final long timestamp = Instant.now().getEpochSecond();
    final String canonicalPayload =
        RequestSignatureValidationService.buildCanonicalPayload(
            "POST", PATH, null, nonce, timestamp);
    final String signature =
        nodeIdentityContext.signBase64(
            signatureAlgorithm, canonicalPayload.getBytes(StandardCharsets.UTF_8));

    final byte[] payloadBytes;
    try {
      payloadBytes = objectMapper.writeValueAsBytes(toBodyMap(request));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize discovery query payload", exception);
    }

    final HttpRequest httpRequest =
        HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header(RequestSignatureValidator.HEADER_NODE_ID, nodeIdentityContext.nodeId())
            .header(RequestSignatureValidator.HEADER_NONCE, nonce)
            .header(RequestSignatureValidator.HEADER_TIMESTAMP, String.valueOf(timestamp))
            .header(RequestSignatureValidator.HEADER_SIGNATURE_ALGORITHM, signatureAlgorithm)
            .header(RequestSignatureValidator.HEADER_SIGNATURE, signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(payloadBytes))
            .build();

    final HttpResponse<String> response;
    try {
      response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new DiscoveryUnreachableException(
          supernodeBaseUrl,
          "Discovery query I/O error against " + uri + ": " + exception.getMessage(),
          exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new DiscoveryUnreachableException(
          supernodeBaseUrl, "Discovery query interrupted against " + uri, exception);
    }

    final int status = response.statusCode();
    if (status >= 500) {
      throw new DiscoveryUnreachableException(
          supernodeBaseUrl,
          "Discovery query failed with HTTP " + status + " body=" + response.body());
    }
    if (status < 200 || status >= 300) {
      // 4xx: bug del caller (request inválida, signature errónea). No es failover-able.
      throw new IllegalStateException(
          "Discovery query rejected with HTTP " + status + " body=" + response.body());
    }

    // Deserializa como Map<String,Object> para evitar el bug de Jackson con el DTO
    // DiscoveryResponsePayload, que tiene accessors record-style sin default constructor.
    final Map<String, Object> body;
    try {
      body =
          objectMapper.readValue(
              response.body(),
              objectMapper
                  .getTypeFactory()
                  .constructMapType(LinkedHashMap.class, String.class, Object.class));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to parse discovery query response", exception);
    }

    final DiscoveryResponse domainResponse = mapToDomain(body);
    LOGGER
        .atDebug()
        .setMessage("Discovery query succeeded")
        .addKeyValue("supernodeBaseUrl", supernodeBaseUrl)
        .addKeyValue("candidates", domainResponse.candidates().size())
        .log();
    return domainResponse;
  }

  @SuppressWarnings("unchecked")
  private static DiscoveryResponse mapToDomain(final Map<String, Object> body) {
    final List<DiscoveryResponse.CandidateNode> candidates = new ArrayList<>();
    final Object rawCandidates = body.get("candidates");
    if (rawCandidates instanceof List<?> list) {
      for (Object entry : list) {
        if (entry instanceof Map<?, ?> map) {
          final Map<String, Object> candidate = (Map<String, Object>) map;
          final String nodeId = stringOrEmpty(candidate.get("nodeId"));
          final String baseUrl = stringOrEmpty(candidate.get("baseUrl"));
          final long bucket = longValue(candidate.get("originalBucketSize"));
          if (!nodeId.isEmpty()) {
            candidates.add(new DiscoveryResponse.CandidateNode(nodeId, baseUrl, bucket));
          }
        }
      }
    }
    return new DiscoveryResponse(candidates);
  }

  private static String stringOrEmpty(final Object value) {
    return value == null ? "" : value.toString();
  }

  private static long longValue(final Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    if (value == null) {
      return 0L;
    }
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException ex) {
      return 0L;
    }
  }

  /**
   * Serializa el {@link DiscoveryRequest} como Map<String,Object> JSON-friendly. Evita el bug de
   * Jackson con el DTO {@code DiscoveryRequestPayload}, que define accessors record-style ({@code
   * nodeId()} en lugar de {@code getNodeId()}) y por defecto {@code BeanSerializer} no los detecta.
   * Mismo patrón que {@code SignedHttpRemoteDiscoveryCandidateClient}.
   */
  private static Map<String, Object> toBodyMap(final DiscoveryRequest request) {
    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("nodeId", request.nodeId());
    body.put("failureDomain", request.failureDomain());
    body.put("requestedBucket", request.requestedBucket());
    body.put("ratio", request.ratio());
    body.put("maxCandidates", request.maxCandidates());
    body.put("targetFailureDomain", request.targetFailureDomain());
    body.put("distributionPlan", request.distributionPlan());
    return body;
  }

  private static String normalizeBaseUrl(final String baseUrl) {
    final String trimmed = baseUrl.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
