package es.ual.node.bootstrap.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.bootstrap.configuration.TestNodeIdentityKeys;
import es.ual.node.recovery.application.RecoveryObservabilityService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Verifica exposición de las métricas canónicas vía {@code /actuator/prometheus} y congela la
 * invariante dual-emit: el valor scraped por Prometheus coincide con el snapshot JSON del mismo
 * servicio en el mismo instante. Cubre también la exposición sobre management.server.port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "management.server.port=0",
      "management.endpoints.web.exposure.include=health,info,prometheus",
      "node.features.recovery-enabled=true",
      // El gauge "discovery queue pending" solo existe en supernodos discovery, este test
      // verifica la lista canónica de métricas, así que el contexto carga el rol supernodo.
      "node.discovery.supernode-role-enabled=true"
    })
class ActuatorPrometheusMetricsIntegrationTest {

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

  @Autowired private RecoveryObservabilityService recoveryService;

  @Value("${local.management.port}")
  private int managementPort;

  @Test
  void exposesCanonicalMetricNamesForAllActiveFamilies() {
    final String body = scrapePrometheusBody();

    // Discovery (gauges)
    assertTrue(body.contains("node_discovery_queue_pending"), "discovery queue pending gauge");
    assertTrue(body.contains("node_discovery_queue_failed"), "discovery queue failed gauge");
    assertTrue(body.contains("node_discovery_candidates_active"), "discovery candidates gauge");

    // Recovery (counters) -- habilitado via feature flag en este test.
    assertTrue(body.contains("node_recovery_cleanup_run_total"), "recovery cleanup run counter");
    assertTrue(
        body.contains("node_recovery_cleanup_run_error_total"), "recovery cleanup error counter");
  }

  @Test
  void scrapedCounterValueMatchesServiceSnapshotAfterEventsRecoveryFamily() {
    recoveryService.onCleanupRun();
    recoveryService.onCleanupRun();
    recoveryService.onCleanupRunError();

    assertEquals(
        recoveryService.snapshot().recoveryCleanupRunTotal(),
        scrapedLong("node_recovery_cleanup_run_total"),
        "Prometheus cleanup.run counter debe coincidir con snapshot");
    assertEquals(
        recoveryService.snapshot().recoveryCleanupRunErrorTotal(),
        scrapedLong("node_recovery_cleanup_run_error_total"),
        "Prometheus cleanup.run.error counter debe coincidir con snapshot");
  }

  private String scrapePrometheusBody() {
    final ResponseEntity<String> response =
        restTemplate.getForEntity(
            "http://localhost:" + managementPort + "/actuator/prometheus", String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    final String body = response.getBody();
    assertNotNull(body);
    return body;
  }

  private long scrapedLong(final String metricName) {
    final String body = scrapePrometheusBody();
    // OpenMetrics: "<name>{labels...} <value>" o "<name> <value>".
    final Pattern line =
        Pattern.compile(
            "^" + Pattern.quote(metricName) + "(?:\\{[^}]*\\})?\\s+([0-9.Ee+-]+)$",
            Pattern.MULTILINE);
    final Matcher matcher = line.matcher(body);
    assertTrue(matcher.find(), "no encontrada linea para metrica " + metricName);
    return (long) Double.parseDouble(matcher.group(1));
  }
}
