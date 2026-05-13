package es.ual.node.bootstrap.observability;

import es.ual.node.bootstrap.configuration.NodeFeaturesProperties;
import es.ual.node.bootstrap.configuration.NodeTopologyProperties;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Evaluates runtime readiness using critical dependencies and controlled degradation rules. */
@Component
public class OperationalReadinessService {

  private static final int DATABASE_VALIDATION_TIMEOUT_SECONDS = 2;

  private final ObjectProvider<DataSource> dataSourceProvider;
  private final NodeFeaturesProperties nodeFeaturesProperties;
  private final NodeTopologyProperties nodeTopologyProperties;
  private final Clock clock;

  /** Creates readiness service. */
  public OperationalReadinessService(
      final ObjectProvider<DataSource> dataSourceProvider,
      final NodeFeaturesProperties nodeFeaturesProperties,
      final NodeTopologyProperties nodeTopologyProperties,
      final Clock clock) {
    if (dataSourceProvider == null
        || nodeFeaturesProperties == null
        || nodeTopologyProperties == null
        || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.dataSourceProvider = dataSourceProvider;
    this.nodeFeaturesProperties = nodeFeaturesProperties;
    this.nodeTopologyProperties = nodeTopologyProperties;
    this.clock = clock;
  }

  /** Returns aggregated readiness snapshot for operational endpoints. */
  public OperationalReadinessPayload snapshot() {
    final Map<String, OperationalReadinessDependencyPayload> dependencies = new LinkedHashMap<>();
    dependencies.put("database.primary", evaluatePrimaryDatabase());
    dependencies.put("topology.tutor", evaluateTutorTopology());
    dependencies.put("topology.discoverySupernodes", evaluateDiscoverySupernodes());
    dependencies.put("topology.recoveryWhitelist", evaluateRecoveryWhitelist());

    final String overallStatus = resolveOverallStatus(dependencies);
    final boolean acceptingTraffic =
        !OperationalReadinessPayload.STATUS_NOT_READY.equals(overallStatus);
    final boolean degraded = OperationalReadinessPayload.STATUS_DEGRADED.equals(overallStatus);
    final Instant checkedAt = clock.instant();

    return new OperationalReadinessPayload(
        overallStatus, acceptingTraffic, degraded, checkedAt, dependencies);
  }

  private String resolveOverallStatus(
      final Map<String, OperationalReadinessDependencyPayload> dependencies) {
    final boolean criticalDependencyDown =
        dependencies.values().stream()
            .anyMatch(dependency -> dependency.critical() && dependency.isDown());
    if (criticalDependencyDown) {
      return OperationalReadinessPayload.STATUS_NOT_READY;
    }

    final boolean hasDegradationSignals =
        dependencies.values().stream().anyMatch(dependency -> !dependency.isUp());
    if (hasDegradationSignals) {
      return OperationalReadinessPayload.STATUS_DEGRADED;
    }

    return OperationalReadinessPayload.STATUS_READY;
  }

  private OperationalReadinessDependencyPayload evaluatePrimaryDatabase() {
    final DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      return new OperationalReadinessDependencyPayload(
          OperationalReadinessDependencyPayload.STATUS_DOWN,
          true,
          "Primary datasource is not configured");
    }

    try (Connection connection = dataSource.getConnection()) {
      if (!connection.isValid(DATABASE_VALIDATION_TIMEOUT_SECONDS)) {
        return new OperationalReadinessDependencyPayload(
            OperationalReadinessDependencyPayload.STATUS_DOWN,
            true,
            "Primary datasource validation failed");
      }
      return new OperationalReadinessDependencyPayload(
          OperationalReadinessDependencyPayload.STATUS_UP, true, "Primary datasource reachable");
    } catch (SQLException exception) {
      return new OperationalReadinessDependencyPayload(
          OperationalReadinessDependencyPayload.STATUS_DOWN,
          true,
          "Primary datasource probe failed: " + exception.getClass().getSimpleName());
    }
  }

  private OperationalReadinessDependencyPayload evaluateTutorTopology() {
    final boolean missingTutorNodeId = isBlank(nodeTopologyProperties.getTutorNodeId());
    final boolean missingTutorBaseUrl = isBlank(nodeTopologyProperties.getTutorBaseUrl());
    final boolean recoveryEnabled = nodeFeaturesProperties.isRecoveryEnabled();

    if (!missingTutorNodeId && !missingTutorBaseUrl) {
      return new OperationalReadinessDependencyPayload(
          OperationalReadinessDependencyPayload.STATUS_UP,
          recoveryEnabled,
          "Tutor topology configured");
    }

    if (recoveryEnabled) {
      return new OperationalReadinessDependencyPayload(
          OperationalReadinessDependencyPayload.STATUS_DOWN,
          true,
          "Recovery enabled but tutor topology is incomplete");
    }

    return new OperationalReadinessDependencyPayload(
        OperationalReadinessDependencyPayload.STATUS_DEGRADED,
        false,
        "Tutor topology incomplete; recovery escalation may be unavailable");
  }

  private OperationalReadinessDependencyPayload evaluateDiscoverySupernodes() {
    if (!nodeFeaturesProperties.isDiscoveryEnabled()) {
      return new OperationalReadinessDependencyPayload(
          OperationalReadinessDependencyPayload.STATUS_UP, false, "Discovery feature disabled");
    }

    final int configuredSupernodes =
        countConfiguredEntries(nodeTopologyProperties.getDiscoverySupernodes());
    if (configuredSupernodes <= 0) {
      return new OperationalReadinessDependencyPayload(
          OperationalReadinessDependencyPayload.STATUS_DEGRADED,
          false,
          "No discovery supernodes configured");
    }

    return new OperationalReadinessDependencyPayload(
        OperationalReadinessDependencyPayload.STATUS_UP,
        false,
        "Discovery supernodes configured: " + configuredSupernodes);
  }

  private OperationalReadinessDependencyPayload evaluateRecoveryWhitelist() {
    if (!nodeFeaturesProperties.isRecoveryEnabled()) {
      return new OperationalReadinessDependencyPayload(
          OperationalReadinessDependencyPayload.STATUS_UP, false, "Recovery feature disabled");
    }

    final int configuredKeys =
        countConfiguredEntries(nodeTopologyProperties.getTutorAcceptedPublicKeys());
    if (configuredKeys <= 0) {
      return new OperationalReadinessDependencyPayload(
          OperationalReadinessDependencyPayload.STATUS_DOWN,
          true,
          "Recovery enabled but tutor accepted public key whitelist is empty");
    }

    return new OperationalReadinessDependencyPayload(
        OperationalReadinessDependencyPayload.STATUS_UP,
        true,
        "Recovery tutor key whitelist configured: " + configuredKeys);
  }

  private static int countConfiguredEntries(final List<String> values) {
    if (values == null || values.isEmpty()) {
      return 0;
    }
    return (int) values.stream().filter(value -> value != null && !value.isBlank()).count();
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
