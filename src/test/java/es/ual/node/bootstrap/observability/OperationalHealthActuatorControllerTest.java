package es.ual.node.bootstrap.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/** Unit tests for {@link OperationalHealthActuatorController}. */
@ExtendWith(MockitoExtension.class)
class OperationalHealthActuatorControllerTest {

  @Mock private OperationalReadinessService operationalReadinessService;

  private OperationalHealthActuatorController controller;

  @BeforeEach
  void setUp() {
    controller = new OperationalHealthActuatorController(operationalReadinessService);
  }

  @Test
  void healthReturnsUpWhenReadinessIsReady() {
    when(operationalReadinessService.snapshot())
        .thenReturn(payload(OperationalReadinessPayload.STATUS_READY));

    final ResponseEntity<Map<String, Object>> response = controller.health();

    assertEquals(200, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals("UP", response.getBody().get("status"));
  }

  @Test
  void healthReturnsDownWhenReadinessIsNotReady() {
    when(operationalReadinessService.snapshot())
        .thenReturn(payload(OperationalReadinessPayload.STATUS_NOT_READY));

    final ResponseEntity<Map<String, Object>> response = controller.health();

    assertEquals(503, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals("DOWN", response.getBody().get("status"));
  }

  private static OperationalReadinessPayload payload(final String status) {
    final boolean acceptingTraffic = !OperationalReadinessPayload.STATUS_NOT_READY.equals(status);
    final boolean degraded = OperationalReadinessPayload.STATUS_DEGRADED.equals(status);

    return new OperationalReadinessPayload(
        status,
        acceptingTraffic,
        degraded,
        Instant.parse("2026-04-20T18:30:00Z"),
        Map.of(
            "database.primary",
            new OperationalReadinessDependencyPayload(
                OperationalReadinessDependencyPayload.STATUS_UP,
                true,
                "Primary datasource reachable")));
  }
}
