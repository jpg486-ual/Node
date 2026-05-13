package es.ual.node.bootstrap.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import es.ual.node.NodeApplication;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** Verifies mandatory node key pair startup validation. */
class NodeIdentityStartupValidationTest {

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner().withUserConfiguration(NodeApplication.class);

  @Test
  void startupFailsWhenNodeKeyPairIsMissing() {
    contextRunner.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasMessageContaining("node.identity.public-key-base64");
        });
  }

  @Test
  void startupSucceedsWhenNodeKeyPairIsConfigured() throws Exception {
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
    keyPairGenerator.initialize(256);
    KeyPair keyPair = keyPairGenerator.generateKeyPair();

    String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    String privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());

    contextRunner
        .withPropertyValues(
            "node.identity.public-key-base64=" + publicKeyBase64,
            "node.identity.private-key-base64=" + privateKeyBase64,
            "node.features.discovery-enabled=false",
            "node.features.negotiation-enabled=false",
            "node.features.custody-enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(NodeIdentityContext.class);
              assertThat(context.getBean(NodeIdentityContext.class).nodeId()).isNotBlank();
            });
  }
}
