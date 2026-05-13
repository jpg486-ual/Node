package es.ual.node.bootstrap.configuration;

import es.ual.node.fragmentstorage.adapters.out.memory.InMemoryCustodyFragmentPayloadPort;
import es.ual.node.fragmentstorage.adapters.out.memory.InMemoryCustodyFragmentPort;
import es.ual.node.fragmentstorage.adapters.out.memory.LocalCapacityCheckAdapter;
import es.ual.node.fragmentstorage.adapters.out.memory.NoOpCustodyEventNotifier;
import es.ual.node.fragmentstorage.application.FragmentCustodyService;
import es.ual.node.fragmentstorage.application.FragmentStorageProperties;
import es.ual.node.fragmentstorage.ports.out.CapacityCheckPort;
import es.ual.node.fragmentstorage.ports.out.CustodyEventNotifierPort;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPayloadPort;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPort;
import es.ual.node.negotiation.application.CapacityProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the general-purpose fragment custody flow. Owns the <strong>custody domain</strong>
 * ports (peer-side asymmetric exchange), disjoint from the recovery domain wired by {@code
 * RecoveryModuleConfiguration}.
 */
@Configuration
public class FragmentStorageModuleConfiguration {

  /** in-memory custody fragment metadata adapter (default persistence mode = memory). */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public CustodyFragmentPort custodyFragmentPort() {
    return new InMemoryCustodyFragmentPort();
  }

  /** in-memory custody fragment payload adapter. */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public CustodyFragmentPayloadPort custodyFragmentPayloadPort() {
    return new InMemoryCustodyFragmentPayloadPort();
  }

  /**
   * default no-op {@link CustodyEventNotifierPort}. Replaced by {@code
   * CustodyLivenessProbeBootstrapAdapter} when {@code node.custody-liveness.enabled=true}
   * (registered in {@code CustodyLivenessModuleConfiguration}). The
   * {@code @ConditionalOnMissingBean} guard ensures exactly one bean of this type is in the
   * context.
   */
  @Bean
  @ConditionalOnMissingBean(CustodyEventNotifierPort.class)
  public CustodyEventNotifierPort defaultCustodyEventNotifier() {
    return new NoOpCustodyEventNotifier();
  }

  /**
   * Provides the fragment custody service. Active only when the node participates in peer-side
   * custody ({@code node.features.custody-enabled=true}).
   */
  @Bean
  @ConditionalOnProperty(prefix = "node.features", name = "custody-enabled", havingValue = "true")
  public FragmentCustodyService fragmentCustodyService(
      final CustodyFragmentPort custodyPort,
      final CustodyFragmentPayloadPort payloadPort,
      final CapacityCheckPort capacityCheckPort,
      final CustodyEventNotifierPort eventNotifier,
      final NodeTopologyProperties topology,
      final FragmentStorageProperties fragmentStorageProperties,
      final Clock clock) {
    return new FragmentCustodyService(
        custodyPort,
        payloadPort,
        capacityCheckPort,
        eventNotifier,
        topology.getAcceptedFragmentSenderKeys(),
        fragmentStorageProperties.getDefaultCustodySeconds(),
        clock);
  }

  /**
   * Adapter local que mide el espacio realmente ocupado por {@code custody_fragment} y lo compara
   * con el cap configurado. Ver {@link LocalCapacityCheckAdapter}.
   */
  @Bean
  public CapacityCheckPort capacityCheckPort(
      final CustodyFragmentPort custodyFragmentPort, final CapacityProperties capacityProperties) {
    return new LocalCapacityCheckAdapter(custodyFragmentPort, capacityProperties.getMaxBytes());
  }
}
