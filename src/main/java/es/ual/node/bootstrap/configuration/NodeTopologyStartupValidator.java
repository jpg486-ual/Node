package es.ual.node.bootstrap.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Validates topology-oriented runtime configuration and emits startup warnings. */
@Component
public class NodeTopologyStartupValidator {

  private static final Logger LOGGER = LoggerFactory.getLogger(NodeTopologyStartupValidator.class);

  /**
   * Creates validator and emits warnings for risky topology setups.
   *
   * @param nodeFeaturesProperties node feature flags
   * @param nodeTopologyProperties node topology properties
   */
  public NodeTopologyStartupValidator(
      final NodeFeaturesProperties nodeFeaturesProperties,
      final NodeTopologyProperties nodeTopologyProperties) {
    if (nodeFeaturesProperties == null || nodeTopologyProperties == null) {
      throw new IllegalArgumentException("Node topology validator dependencies must not be null");
    }

    warnIfTutorNotConfigured(nodeTopologyProperties);
    warnIfDiscoverySupernodesMissing(nodeTopologyProperties);
    warnIfRecoveryEnabledWithoutTutorWhitelist(nodeFeaturesProperties, nodeTopologyProperties);
  }

  private void warnIfTutorNotConfigured(final NodeTopologyProperties nodeTopologyProperties) {
    final boolean missingTutorNodeId =
        nodeTopologyProperties.getTutorNodeId() == null
            || nodeTopologyProperties.getTutorNodeId().isBlank();
    final boolean missingTutorBaseUrl =
        nodeTopologyProperties.getTutorBaseUrl() == null
            || nodeTopologyProperties.getTutorBaseUrl().isBlank();

    if (missingTutorNodeId || missingTutorBaseUrl) {
      LOGGER.warn(
          "Node started without tutor supernode fully configured. "
              + "Set node.topology.tutorNodeId and node.topology.tutorBaseUrl");
    }
  }

  private void warnIfDiscoverySupernodesMissing(
      final NodeTopologyProperties nodeTopologyProperties) {
    if (nodeTopologyProperties.getDiscoverySupernodes().isEmpty()) {
      LOGGER.warn(
          "Node started without discovery supernodes. "
              + "Set node.topology.discoverySupernodes for resilient candidate discovery");
    }
  }

  private void warnIfRecoveryEnabledWithoutTutorWhitelist(
      final NodeFeaturesProperties nodeFeaturesProperties,
      final NodeTopologyProperties nodeTopologyProperties) {
    if (nodeFeaturesProperties.isRecoveryEnabled()
        && nodeTopologyProperties.getTutorAcceptedPublicKeys().isEmpty()) {
      LOGGER.warn(
          "Recovery capability is enabled but node.topology.tutorAcceptedPublicKeys is empty. "
              + "Tutor should whitelist allowed node public keys for temporary fragment custody");
    }
  }
}
