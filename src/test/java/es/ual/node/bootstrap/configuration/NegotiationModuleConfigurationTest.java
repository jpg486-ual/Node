package es.ual.node.bootstrap.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.negotiation.application.CapacityProperties;
import es.ual.node.negotiation.ports.out.CapacityPort;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link NegotiationModuleConfiguration}. */
class NegotiationModuleConfigurationTest {

  @Test
  void capacityPortUsesConfiguredMaxBytes() {
    NegotiationModuleConfiguration configuration = new NegotiationModuleConfiguration();
    CapacityProperties properties = new CapacityProperties();
    properties.setMaxBytes(100L);

    CapacityPort capacityPort = configuration.capacityPort(properties);

    assertTrue(capacityPort.canReserve(100L));
    assertFalse(capacityPort.canReserve(101L));
  }

  @Test
  void capacityPropertiesRejectNonPositiveValues() {
    CapacityProperties properties = new CapacityProperties();

    assertThrows(IllegalArgumentException.class, () -> properties.setMaxBytes(0L));
  }
}
