package es.ual.node.bootstrap.configuration;

import es.ual.node.identitysecurity.ports.out.PublicKeyRegistry;
import es.ual.node.negotiation.adapters.out.memory.InMemoryAgreementRepository;
import es.ual.node.negotiation.adapters.out.memory.InMemoryCapacityPort;
import es.ual.node.negotiation.application.CapacityProperties;
import es.ual.node.negotiation.application.NegotiationProperties;
import es.ual.node.negotiation.application.NegotiationService;
import es.ual.node.negotiation.ports.out.AgreementRepository;
import es.ual.node.negotiation.ports.out.CapacityPort;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the negotiation module, admission control surface (CRUD agreements +
 * capacity ledger). El módulo expone {@code NegotiationController} (4 endpoints CRUD activos tras
 * deshabilitar `reject`) y un puerto cross-module load-bearing: {@code AgreementRepository}
 * (consumido por custodyliveness para keep-list legacy fallback). El antiguo bean {@code
 * FragmentQueuePort} fue retirado: la cola `queued_fragment` no se alimenta en el modelo cerrado
 * actual.
 */
@Configuration
public class NegotiationModuleConfiguration {

  /** Provides system clock bean. */
  @Bean
  public Clock negotiationClock() {
    return Clock.systemUTC();
  }

  /** Provides in-memory agreement repository. */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public AgreementRepository agreementRepository(final Clock clock) {
    return new InMemoryAgreementRepository(clock);
  }

  /** Provides in-memory capacity port. */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public CapacityPort capacityPort(final CapacityProperties capacityProperties) {
    return new InMemoryCapacityPort(capacityProperties.getMaxBytes());
  }

  /** Provides negotiation service. */
  @Bean
  public NegotiationService negotiationService(
      final PublicKeyRegistry publicKeyRegistry,
      final AgreementRepository agreementRepository,
      final CapacityPort capacityPort,
      final NegotiationProperties properties,
      final Clock clock) {
    return new NegotiationService(
        publicKeyRegistry, agreementRepository, capacityPort, properties, clock);
  }
}
