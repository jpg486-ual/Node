package es.ual.node.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.discovery.adapters.out.http.SignedHttpRemoteDiscoveryCandidateClient;
import es.ual.node.discovery.adapters.out.http.SignedHttpRemoteDiscoveryQueryClient;
import es.ual.node.discovery.adapters.out.memory.InMemoryDiscoveryCandidateDirectoryAdapter;
import es.ual.node.discovery.adapters.out.memory.InMemoryDiscoveryRetryQueuePort;
import es.ual.node.discovery.application.DiscoveryCandidateCleanupWorker;
import es.ual.node.discovery.application.DiscoveryCandidateDirectoryService;
import es.ual.node.discovery.application.DiscoveryObservabilityService;
import es.ual.node.discovery.application.DiscoveryProperties;
import es.ual.node.discovery.application.DiscoveryQueryCache;
import es.ual.node.discovery.application.DiscoveryRetryProperties;
import es.ual.node.discovery.application.DiscoveryRetryQueueService;
import es.ual.node.discovery.application.DiscoveryRetryWorker;
import es.ual.node.discovery.application.DiscoveryService;
import es.ual.node.discovery.application.SelfDiscoveryRegistrar;
import es.ual.node.discovery.application.SelfDiscoveryRenewalWorker;
import es.ual.node.discovery.ports.out.DiscoveryCandidateDirectoryPort;
import es.ual.node.discovery.ports.out.DiscoveryRetryQueuePort;
import es.ual.node.discovery.ports.out.RemoteDiscoveryCandidateClientPort;
import es.ual.node.discovery.ports.out.RemoteDiscoveryQueryClientPort;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.identitysecurity.ports.out.PublicKeyRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for discovery module.
 *
 * <p>Split en dos lados:
 *
 * <ul>
 *   <li><b>Server-side (supernodo discovery)</b> por {@code
 *       node.discovery.supernode-role-enabled=true}. Mantiene la tabla local {@code
 *       discovery_candidate}, sirve PUT registrations y POST queries inter-node, corre el cleanup
 *       periódico y el retry-worker para queries con directorio vacío.
 *   <li><b>Client-side (cualquier nodo que haga upload)</b> por {@code
 *       node.discovery.self-register-enabled=true} (push) y {@code node.features.discovery-enabled}
 *       (cliente HTTP query remoto). Tiene el {@code SelfDiscoveryRegistrar} que se anuncia ante
 *       supernodos remotos y el {@code SignedHttpRemoteDiscoveryQueryClient} que consulta
 *       supernodos para resolver custodios en el upload pipeline.
 * </ul>
 *
 * <p>El default es: rol cliente (cualquier nodo). Para que un nodo sea supernodo discovery hay que
 * solicitarlo explícitamente con la property nueva.
 */
@Configuration
public class DiscoveryModuleConfiguration {

  // ---------- Server-side (supernodo discovery) ----------

  /**
   * Provides candidate directory port implementation. Sólo en supernodos, los nodos comunes no
   * tienen tabla local porque consultan remotamente vía {@code
   * SignedHttpRemoteDiscoveryQueryClient}.
   *
   * @return candidate directory
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.discovery",
      name = "supernode-role-enabled",
      havingValue = "true")
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public DiscoveryCandidateDirectoryPort discoveryCandidateDirectoryPort(final Clock clock) {
    return new InMemoryDiscoveryCandidateDirectoryAdapter(clock);
  }

  /**
   * Provides in-memory durable retry queue for discovery requests. Sólo supernodos: la cola
   * persiste queries que llegaron al supernodo con directorio vacío y se reintentan más tarde. Los
   * nodos comunes no encuentran retry queue, su cliente remoto se encarga de failover al supernodo
   * siguiente.
   *
   * @return retry queue port
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.discovery",
      name = "supernode-role-enabled",
      havingValue = "true")
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public DiscoveryRetryQueuePort discoveryRetryQueuePort() {
    return new InMemoryDiscoveryRetryQueuePort();
  }

  /**
   * Provides discovery service. Sólo supernodos.
   *
   * @param publicKeyRegistry public key registry
   * @param discoveryCandidateDirectoryPort candidate directory
   * @param discoveryProperties discovery properties
   * @return discovery service
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.discovery",
      name = "supernode-role-enabled",
      havingValue = "true")
  public DiscoveryService discoveryService(
      final PublicKeyRegistry publicKeyRegistry,
      final DiscoveryCandidateDirectoryPort discoveryCandidateDirectoryPort,
      final DiscoveryProperties discoveryProperties) {
    return new DiscoveryService(
        publicKeyRegistry, discoveryCandidateDirectoryPort, discoveryProperties);
  }

  /**
   * Provides discovery retry queue service. Sólo supernodos.
   *
   * @param discoveryRetryQueuePort queue port
   * @param discoveryRetryProperties retry properties
   * @param clock system clock
   * @return queue service
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.discovery",
      name = "supernode-role-enabled",
      havingValue = "true")
  public DiscoveryRetryQueueService discoveryRetryQueueService(
      final DiscoveryRetryQueuePort discoveryRetryQueuePort,
      final DiscoveryRetryProperties discoveryRetryProperties,
      final Clock clock) {
    return new DiscoveryRetryQueueService(discoveryRetryQueuePort, discoveryRetryProperties, clock);
  }

  /**
   * Provides candidate directory service. Sólo supernodos.
   *
   * @param discoveryCandidateDirectoryPort candidate directory
   * @return candidate directory service
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.discovery",
      name = "supernode-role-enabled",
      havingValue = "true")
  public DiscoveryCandidateDirectoryService discoveryCandidateDirectoryService(
      final DiscoveryCandidateDirectoryPort discoveryCandidateDirectoryPort) {
    return new DiscoveryCandidateDirectoryService(discoveryCandidateDirectoryPort);
  }

  /**
   * Provides discovery observability service. Sólo supernodos (lee del directorio + retry queue
   * locales).
   *
   * @param discoveryRetryQueuePort retry queue port
   * @param discoveryCandidateDirectoryPort candidate directory port
   * @return observability service
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.discovery",
      name = "supernode-role-enabled",
      havingValue = "true")
  public DiscoveryObservabilityService discoveryObservabilityService(
      final DiscoveryRetryQueuePort discoveryRetryQueuePort,
      final DiscoveryCandidateDirectoryPort discoveryCandidateDirectoryPort) {
    return new DiscoveryObservabilityService(
        discoveryRetryQueuePort, discoveryCandidateDirectoryPort);
  }

  /**
   * Provides retry worker for pending discovery requests. Sólo supernodos.
   *
   * @param discoveryService discovery service
   * @param discoveryRetryQueueService retry queue service
   * @return worker
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.discovery",
      name = "supernode-role-enabled",
      havingValue = "true")
  public DiscoveryRetryWorker discoveryRetryWorker(
      final DiscoveryService discoveryService,
      final DiscoveryRetryQueueService discoveryRetryQueueService,
      final io.micrometer.observation.ObservationRegistry observationRegistry) {
    return new DiscoveryRetryWorker(
        discoveryService, discoveryRetryQueueService, observationRegistry);
  }

  /**
   * Provides the signed HTTP client used by self-discovery registration to upsert the local
   * candidate profile in remote supernodes' directories.
   *
   * @param nodeIdentityContext local node identity (signs PUTs)
   * @param objectMapper Jackson mapper to serialize the upsert body
   * @return remote candidate client adapter
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.discovery",
      name = "self-register-enabled",
      havingValue = "true")
  public RemoteDiscoveryCandidateClientPort remoteDiscoveryCandidateClientPort(
      final NodeIdentityContext nodeIdentityContext, final ObjectMapper objectMapper) {
    return new SignedHttpRemoteDiscoveryCandidateClient(
        nodeIdentityContext, objectMapper, "SHA256withECDSA");
  }

  /**
   * Provides the signed HTTP client used by cualquier nodo (común o supernodo) que haga upload para
   * consultar candidatos a un supernodo discovery remoto via {@code POST /ops/discovery/query}.
   * Default-on cuando el módulo discovery está activo; el upload pipeline lo inyecta por {@code
   * ObjectProvider} y cae al fallback estático cuando está ausente.
   *
   * @param nodeIdentityContext local node identity (signs POSTs)
   * @param objectMapper Jackson mapper to serialize the discover query body
   * @return remote discovery query client adapter
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.features",
      name = "discovery-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public RemoteDiscoveryQueryClientPort remoteDiscoveryQueryClientPort(
      final NodeIdentityContext nodeIdentityContext, final ObjectMapper objectMapper) {
    return new SignedHttpRemoteDiscoveryQueryClient(
        nodeIdentityContext, objectMapper, "SHA256withECDSA");
  }

  /**
   * Provides the self-discovery registrar bean.
   *
   * @param nodeIdentityContext local node identity
   * @param remoteClient signed HTTP client for upserts
   * @param topology topology configuration with the supernodes list
   * @param failureDomain local failure domain (zone-a/rack-1, etc.)
   * @return self-discovery registrar
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.discovery",
      name = "self-register-enabled",
      havingValue = "true")
  public SelfDiscoveryRegistrar selfDiscoveryRegistrar(
      final NodeIdentityContext nodeIdentityContext,
      final RemoteDiscoveryCandidateClientPort remoteClient,
      final NodeTopologyProperties topology,
      @Value("${node.failure-domain:}") final String failureDomain,
      @Value("${node.discovery.local-base-url:}") final String localBaseUrl) {
    return new SelfDiscoveryRegistrar(
        nodeIdentityContext,
        remoteClient,
        topology.getDiscoverySupernodes(),
        failureDomain,
        localBaseUrl);
  }

  /**
   * Wires the registrar to fire once the application context is fully ready (so the local HTTP port
   * is already bound and inbound signed requests can be served).
   *
   * @param registrar self-discovery registrar
   * @return application listener that triggers the registration on startup
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.discovery",
      name = "self-register-enabled",
      havingValue = "true")
  public ApplicationListener<ApplicationReadyEvent> selfDiscoveryRegistrarListener(
      final SelfDiscoveryRegistrar registrar) {
    return event -> registrar.registerSelf();
  }

  /**
   * Provides the periodic renewal worker. Same conditional gate as the registrar; the
   * {@code @Scheduled} annotation on {@code renew()} drives the cadence.
   *
   * @param registrar registrar wrapped by the worker
   * @return renewal worker bean
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.discovery",
      name = "self-register-enabled",
      havingValue = "true")
  public SelfDiscoveryRenewalWorker selfDiscoveryRenewalWorker(
      final SelfDiscoveryRegistrar registrar) {
    return new SelfDiscoveryRenewalWorker(registrar);
  }

  /**
   * Provides the supernode-side cleanup worker. Sólo supernodos, purga el directorio local. {@code
   * node.discovery.cleanup.enabled=false} lo desactiva.
   *
   * @param directoryPort candidate directory port
   * @param clock clock for staleness threshold
   * @param stalenessSeconds rows older than this are pruned
   * @return cleanup worker bean
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.discovery",
      name = "supernode-role-enabled",
      havingValue = "true")
  @ConditionalOnProperty(
      prefix = "node.discovery.cleanup",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public DiscoveryCandidateCleanupWorker discoveryCandidateCleanupWorker(
      final DiscoveryCandidateDirectoryPort directoryPort,
      final Clock clock,
      @Value("${node.discovery.cleanup.staleness-seconds:900}") final long stalenessSeconds) {
    return new DiscoveryCandidateCleanupWorker(directoryPort, clock, stalenessSeconds);
  }

  /**
   * Provides the origin-side TTL cache over {@link DiscoveryService#discover} responses.
   * Default-on; disable with {@code node.discovery.cache.enabled=false}.
   *
   * @param clock clock for expiry
   * @param ttlSeconds entry lifetime
   * @return cache bean
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.discovery.cache",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public DiscoveryQueryCache discoveryQueryCache(
      final Clock clock, @Value("${node.discovery.cache.ttl-seconds:30}") final long ttlSeconds) {
    return new DiscoveryQueryCache(clock, ttlSeconds);
  }
}
