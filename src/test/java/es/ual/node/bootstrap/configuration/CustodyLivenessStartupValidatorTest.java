package es.ual.node.bootstrap.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import es.ual.node.fragmentstorage.application.FragmentStorageProperties;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CustodyLivenessStartupValidator}. */
class CustodyLivenessStartupValidatorTest {

  @Test
  void acceptsValidConfiguration() {
    final CustodyLivenessProperties liveness = new CustodyLivenessProperties();
    liveness.setBaseIntervalSeconds(60L);
    liveness.setRenewalHorizonSeconds(120L);
    final FragmentStorageProperties storage = new FragmentStorageProperties();
    storage.setDefaultCustodySeconds(600L);

    assertThatCode(() -> new CustodyLivenessStartupValidator(liveness, storage))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsBaseIntervalGreaterThanOrEqualToRenewalHorizon() {
    final CustodyLivenessProperties liveness = new CustodyLivenessProperties();
    liveness.setBaseIntervalSeconds(120L);
    liveness.setRenewalHorizonSeconds(120L);
    final FragmentStorageProperties storage = new FragmentStorageProperties();
    storage.setDefaultCustodySeconds(600L);

    assertThatThrownBy(() -> new CustodyLivenessStartupValidator(liveness, storage))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("baseIntervalSeconds")
        .hasMessageContaining("renewalHorizonSeconds");
  }

  @Test
  void rejectsRenewalHorizonGreaterThanOrEqualToDefaultCustody() {
    final CustodyLivenessProperties liveness = new CustodyLivenessProperties();
    liveness.setBaseIntervalSeconds(60L);
    liveness.setRenewalHorizonSeconds(600L);
    final FragmentStorageProperties storage = new FragmentStorageProperties();
    storage.setDefaultCustodySeconds(600L);

    assertThatThrownBy(() -> new CustodyLivenessStartupValidator(liveness, storage))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("renewalHorizonSeconds")
        .hasMessageContaining("default-custody-seconds");
  }

  @Test
  void rejectsNonPositiveValues() {
    final CustodyLivenessProperties liveness = new CustodyLivenessProperties();
    liveness.setBaseIntervalSeconds(0L);
    liveness.setRenewalHorizonSeconds(120L);
    final FragmentStorageProperties storage = new FragmentStorageProperties();
    storage.setDefaultCustodySeconds(600L);

    assertThatThrownBy(() -> new CustodyLivenessStartupValidator(liveness, storage))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be positive");
  }

  @Test
  void rejectsNullDependencies() {
    final CustodyLivenessProperties liveness = new CustodyLivenessProperties();
    final FragmentStorageProperties storage = new FragmentStorageProperties();

    assertThatThrownBy(() -> new CustodyLivenessStartupValidator(null, storage))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CustodyLivenessStartupValidator(liveness, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
