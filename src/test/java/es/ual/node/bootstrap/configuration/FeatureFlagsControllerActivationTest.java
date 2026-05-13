package es.ual.node.bootstrap.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import es.ual.node.NodeApplication;
import es.ual.node.discovery.adapters.in.web.DiscoveryOpsController;
import es.ual.node.fragmentstorage.adapters.in.web.CustodyController;
import es.ual.node.negotiation.adapters.in.web.NegotiationController;
import es.ual.node.recovery.adapters.in.web.RecoveryController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** Verifies controller activation based on feature flags. */
class FeatureFlagsControllerActivationTest {

  private WebApplicationContextRunner contextRunner() {
    return new WebApplicationContextRunner()
        .withUserConfiguration(NodeApplication.class)
        .withPropertyValues(TestNodeIdentityKeys.generatePropertyValues())
        .withPropertyValues(
            "node.features.discovery-enabled=true",
            "node.discovery.supernode-role-enabled=true",
            "node.features.negotiation-enabled=true",
            "node.features.negotiation-formal-enabled=true",
            "node.features.custody-enabled=true",
            "node.features.recovery-enabled=true",
            "node.topology.tutorAcceptedPublicKeys=test-public-key");
  }

  @Test
  void allControllersEnabledByDefaultFlags() {
    contextRunner()
        .run(
            context -> {
              // Las queries inter-node viven en DiscoveryOpsController (POST /ops/discovery/query)
              // gateado por node.discovery.supernode-role-enabled=true.
              assertThat(context).hasSingleBean(DiscoveryOpsController.class);
              assertThat(context).hasSingleBean(NegotiationController.class);
              assertThat(context).hasSingleBean(CustodyController.class);
              assertThat(context).hasSingleBean(RecoveryController.class);
            });
  }

  @Test
  void discoveryOpsControllerDisabledWhenSupernodeRoleIsFalse() {
    contextRunner()
        .withPropertyValues("node.discovery.supernode-role-enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(DiscoveryOpsController.class));
  }

  @Test
  void negotiationControllerDisabledWhenFormalFlagIsFalse() {
    contextRunner()
        .withPropertyValues("node.features.negotiation-formal-enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(NegotiationController.class));
  }

  @Test
  void custodyControllerDisabledWhenFlagIsFalse() {
    contextRunner()
        .withPropertyValues("node.features.custody-enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(CustodyController.class));
  }

  @Test
  void recoveryControllerDisabledWhenFlagIsFalse() {
    contextRunner()
        .withPropertyValues("node.features.recovery-enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(RecoveryController.class));
  }
}
