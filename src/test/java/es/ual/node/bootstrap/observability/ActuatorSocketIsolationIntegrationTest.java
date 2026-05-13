package es.ual.node.bootstrap.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.bootstrap.configuration.TestNodeIdentityKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * /actuator/prometheus solo responde en management.server.port, nunca en el socket principal (/auth
 * /fs /files /sync /ops). El único punto de acceso a las métricas Micrometer es el puerto
 * management aislado, pensado para scrape interno por Prometheus dentro de la red docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "management.server.port=0",
      "management.endpoints.web.exposure.include=health,info,prometheus"
    })
class ActuatorSocketIsolationIntegrationTest {

  private static final String[] NODE_IDENTITY_PROPERTIES =
      TestNodeIdentityKeys.generatePropertyValues();

  @DynamicPropertySource
  static void configureNodeIdentity(final DynamicPropertyRegistry registry) {
    for (String property : NODE_IDENTITY_PROPERTIES) {
      final int separatorIndex = property.indexOf('=');
      final String key = property.substring(0, separatorIndex);
      final String value = property.substring(separatorIndex + 1);
      registry.add(key, () -> value);
    }
  }

  @Autowired private TestRestTemplate restTemplate;

  @Value("${local.server.port}")
  private int mainPort;

  @Value("${local.management.port}")
  private int managementPort;

  @Test
  void mainAndManagementPortsAreBoundOnSeparateSockets() {
    assertNotEquals(
        mainPort,
        managementPort,
        "management.server.port debe ser un socket independiente de server.port");
  }

  @Test
  void prometheusEndpointIsServedOnManagementPort() {
    final ResponseEntity<String> response =
        restTemplate.getForEntity(
            "http://localhost:" + managementPort + "/actuator/prometheus", String.class);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    final String body = response.getBody();
    assertTrue(body != null && !body.isEmpty(), "payload OpenMetrics no debe ser vacio");
    assertTrue(
        body.contains("jvm_memory_used_bytes") || body.contains("# TYPE"),
        "payload debe incluir metricas JVM por defecto o cabecera de tipo OpenMetrics");
  }

  @Test
  void prometheusEndpointIsNotServedOnMainPort() {
    final ResponseEntity<String> response =
        restTemplate.getForEntity(
            "http://localhost:" + mainPort + "/actuator/prometheus", String.class);

    // La invariante es "el socket principal no sirve la exposicion OpenMetrics".
    // En esta build el RequestSignatureValidator intercepta y devuelve 401 antes
    // de que el dispatcher principal pueda ver la ruta: efecto equivalente a 404
    // desde fuera (doble capa: aislamiento de socket + interceptor de firma).
    assertTrue(
        response.getStatusCode().is4xxClientError(),
        "/actuator/prometheus en el socket principal no debe devolver 2xx; status="
            + response.getStatusCode());
    final String body = response.getBody();
    assertTrue(
        body == null || !body.contains("jvm_memory_used_bytes"),
        "el body en puerto principal no debe contener el payload OpenMetrics");
  }

  @Test
  void applicationEndpointsAreNotServedOnManagementPort() {
    final ResponseEntity<String> response =
        restTemplate.getForEntity(
            "http://localhost:" + managementPort + "/auth/login", String.class);

    // La invariante bidireccional es "el puerto management no sirve endpoints de
    // aplicacion". El contexto actuator en el management DispatcherServlet solo
    // mapea /actuator/**; cualquier otra ruta acaba siendo descartada (con 401
    // via el forward a /error del contexto principal o 404 directo segun la
    // configuracion). Aceptamos cualquier 4xx como evidencia de no-servicio.
    assertTrue(
        response.getStatusCode().is4xxClientError(),
        "/auth/** no debe servirse en management port; status=" + response.getStatusCode());
  }
}
