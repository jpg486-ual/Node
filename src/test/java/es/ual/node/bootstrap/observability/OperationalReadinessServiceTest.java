package es.ual.node.bootstrap.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import es.ual.node.bootstrap.configuration.NodeFeaturesProperties;
import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** Unit tests for {@link OperationalReadinessService}. */
@ExtendWith(MockitoExtension.class)
class OperationalReadinessServiceTest {

  @Mock private ObjectProvider<DataSource> dataSourceProvider;

  @Mock private DataSource dataSource;

  @Mock private Connection connection;

  private NodeFeaturesProperties nodeFeaturesProperties;
  private NodeTopologyProperties nodeTopologyProperties;
  private OperationalReadinessService service;

  @BeforeEach
  void setUp() throws SQLException {
    nodeFeaturesProperties = new NodeFeaturesProperties();
    nodeFeaturesProperties.setDiscoveryEnabled(true);
    nodeFeaturesProperties.setRecoveryEnabled(false);

    nodeTopologyProperties = new NodeTopologyProperties();
    nodeTopologyProperties.setTutorNodeId("node-tutor");
    nodeTopologyProperties.setTutorBaseUrl("http://localhost:8081");
    nodeTopologyProperties.setDiscoverySupernodes(List.of("http://localhost:8081"));
    nodeTopologyProperties.setTutorAcceptedPublicKeys(List.of("base64-key-a"));

    lenient().when(dataSourceProvider.getIfAvailable()).thenReturn(dataSource);
    lenient().when(dataSource.getConnection()).thenReturn(connection);
    lenient().when(connection.isValid(2)).thenReturn(true);

    service =
        new OperationalReadinessService(
            dataSourceProvider,
            nodeFeaturesProperties,
            nodeTopologyProperties,
            Clock.fixed(Instant.parse("2026-04-20T18:30:00Z"), ZoneOffset.UTC));
  }

  @Test
  void snapshotReturnsReadyWhenAllDependenciesAreHealthy() {
    final OperationalReadinessPayload payload = service.snapshot();

    assertEquals(OperationalReadinessPayload.STATUS_READY, payload.status());
    assertTrue(payload.acceptingTraffic());
    assertFalse(payload.degraded());
    assertEquals(Instant.parse("2026-04-20T18:30:00Z"), payload.checkedAt());
    assertEquals(
        OperationalReadinessDependencyPayload.STATUS_UP,
        payload.dependencies().get("database.primary").status());
  }

  @Test
  void snapshotReturnsDegradedWhenDiscoverySupernodesAreMissing() {
    nodeTopologyProperties.setDiscoverySupernodes(List.of());

    final OperationalReadinessPayload payload = service.snapshot();

    assertEquals(OperationalReadinessPayload.STATUS_DEGRADED, payload.status());
    assertTrue(payload.acceptingTraffic());
    assertTrue(payload.degraded());
    assertEquals(
        OperationalReadinessDependencyPayload.STATUS_DEGRADED,
        payload.dependencies().get("topology.discoverySupernodes").status());
  }

  @Test
  void snapshotReturnsNotReadyWhenRecoveryEnabledWithoutTutorTopology() {
    nodeFeaturesProperties.setRecoveryEnabled(true);
    nodeTopologyProperties.setTutorNodeId(" ");
    nodeTopologyProperties.setTutorBaseUrl(" ");

    final OperationalReadinessPayload payload = service.snapshot();

    assertEquals(OperationalReadinessPayload.STATUS_NOT_READY, payload.status());
    assertFalse(payload.acceptingTraffic());
    assertFalse(payload.degraded());
    assertEquals(
        OperationalReadinessDependencyPayload.STATUS_DOWN,
        payload.dependencies().get("topology.tutor").status());
  }

  @Test
  void snapshotReturnsNotReadyWhenDatabaseProbeFails() throws SQLException {
    when(dataSource.getConnection()).thenThrow(new SQLException("db-down"));

    final OperationalReadinessPayload payload = service.snapshot();

    assertEquals(OperationalReadinessPayload.STATUS_NOT_READY, payload.status());
    assertFalse(payload.acceptingTraffic());
    assertEquals(
        OperationalReadinessDependencyPayload.STATUS_DOWN,
        payload.dependencies().get("database.primary").status());
  }
}
