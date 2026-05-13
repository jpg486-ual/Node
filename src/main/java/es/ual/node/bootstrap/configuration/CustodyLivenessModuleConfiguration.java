package es.ual.node.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.custodyliveness.adapters.out.custody.CustodyFragmentInventoryAdapter;
import es.ual.node.custodyliveness.adapters.out.custody.CustodyFragmentLifecycleAdapter;
import es.ual.node.custodyliveness.adapters.out.http.SignedHttpRemoteCustodyProbeClient;
import es.ual.node.custodyliveness.adapters.out.http.SignedHttpRemoteOriginKeepListClient;
import es.ual.node.custodyliveness.adapters.out.memory.InMemoryCustodyProbeSessionPort;
import es.ual.node.custodyliveness.adapters.out.memory.InMemoryOriginCustodianHealthPort;
import es.ual.node.custodyliveness.adapters.out.negotiation.AgreementBackedCustodyFragmentInterestPort;
import es.ual.node.custodyliveness.adapters.out.probe.CustodyLivenessProbeBootstrapAdapter;
import es.ual.node.custodyliveness.adapters.out.recovery.TutorReturnCustodyEscalationPort;
import es.ual.node.custodyliveness.application.CustodianOutboundKeepListService;
import es.ual.node.custodyliveness.application.CustodianProbeWorker;
import es.ual.node.custodyliveness.application.CustodyLivenessObservabilityService;
import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import es.ual.node.custodyliveness.application.CustodyLivenessService;
import es.ual.node.custodyliveness.application.CustodyLivenessWorker;
import es.ual.node.custodyliveness.application.OriginInboundKeepListService;
import es.ual.node.custodyliveness.application.OriginInverseProbeService;
import es.ual.node.custodyliveness.application.OriginInverseProbeWorker;
import es.ual.node.custodyliveness.ports.out.CustodyEscalationPort;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentInterestPort;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentInventoryPort;
import es.ual.node.custodyliveness.ports.out.CustodyFragmentLifecyclePort;
import es.ual.node.custodyliveness.ports.out.CustodyProbeSessionPort;
import es.ual.node.custodyliveness.ports.out.OriginCustodianHealthPort;
import es.ual.node.custodyliveness.ports.out.RemoteCustodyProbeClientPort;
import es.ual.node.custodyliveness.ports.out.RemoteOriginKeepListClientPort;
import es.ual.node.filesystem.ports.out.FragmentPlacementPort;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.fragmentstorage.ports.out.CustodyEventNotifierPort;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPayloadPort;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPort;
import es.ual.node.fragmentstorage.ports.out.RemoteCustodyInventoryClientPort;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.negotiation.ports.out.AgreementRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration for custody liveness module. */
@Configuration
public class CustodyLivenessModuleConfiguration {

  /** Provides in-memory session port. */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public CustodyProbeSessionPort custodyProbeSessionPort() {
    return new InMemoryCustodyProbeSessionPort();
  }

  /** Provides agreement-backed interest evaluator for incoming custody probes. */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public CustodyFragmentInterestPort custodyFragmentInterestPort(
      final AgreementRepository agreementRepository,
      final NodeIdentityContext nodeIdentityContext,
      final FsEntryPort fsEntryPort,
      final FragmentPlacementPort fragmentPlacementPort,
      final CustodyLivenessProperties livenessProperties,
      final Clock clock) {
    return new AgreementBackedCustodyFragmentInterestPort(
        agreementRepository,
        nodeIdentityContext,
        fsEntryPort,
        fragmentPlacementPort,
        livenessProperties,
        clock);
  }

  /**
   * Custody backed inventory for outbound liveness probes. Reads only the custody domain:
   * recovery_orphan fragments do not participate in probe cycles.
   */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public CustodyFragmentInventoryPort custodyFragmentInventoryPort(
      final CustodyFragmentPort custodyFragmentPort) {
    return new CustodyFragmentInventoryAdapter(custodyFragmentPort);
  }

  /**
   * Escalation adapter for unresponsive custody probes. Reads from the custody domain, POSTs to the
   * requester's tutor (which writes into recovery_orphan_fragment), then deletes from custody.
   * Explicit one-way state transition between two physically disjoint tables.
   */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public CustodyEscalationPort custodyEscalationPort(
      final NodeTopologyProperties nodeTopologyProperties,
      final NodeIdentityContext nodeIdentityContext,
      final CustodyFragmentPort custodyFragmentPort,
      final CustodyFragmentPayloadPort custodyFragmentPayloadPort,
      final CustodyLivenessProperties custodyLivenessProperties,
      final es.ual.node.custodyliveness.ports.out.CustodyFragmentLifecyclePort
          fragmentLifecyclePort,
      final es.ual.node.custodyliveness.application.CustodyLivenessObservabilityService
          custodyLivenessObservabilityService,
      final SecurityProperties securityProperties,
      final io.micrometer.tracing.Tracer tracer,
      final es.ual.node.bootstrap.observability.TraceContextHttpInjector traceInjector) {
    return new TutorReturnCustodyEscalationPort(
        nodeTopologyProperties,
        nodeIdentityContext,
        custodyFragmentPort,
        custodyFragmentPayloadPort,
        custodyLivenessProperties,
        fragmentLifecyclePort,
        custodyLivenessObservabilityService,
        securityProperties.getDefaultSignatureAlgorithm(),
        tracer,
        traceInjector);
  }

  /** Provides signed remote client for custody probes. */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public RemoteCustodyProbeClientPort remoteCustodyProbeClientPort(
      final NodeIdentityContext nodeIdentityContext,
      final NodeTopologyProperties nodeTopologyProperties,
      final CustodyLivenessProperties custodyLivenessProperties,
      final ObjectMapper objectMapper,
      final SecurityProperties securityProperties,
      final io.micrometer.tracing.Tracer tracer,
      final es.ual.node.bootstrap.observability.TraceContextHttpInjector traceInjector) {
    return new SignedHttpRemoteCustodyProbeClient(
        nodeIdentityContext,
        nodeTopologyProperties,
        custodyLivenessProperties,
        objectMapper,
        securityProperties.getDefaultSignatureAlgorithm(),
        tracer,
        traceInjector);
  }

  /** Provides custody liveness observability counters. */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public CustodyLivenessObservabilityService custodyLivenessObservabilityService() {
    return new CustodyLivenessObservabilityService();
  }

  /**
   * Adapter that exposes lifecycle controls of locally custodied fragments (extend/release) on the
   * {@code custodyliveness} side of the hexagonal frontier.
   */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public es.ual.node.custodyliveness.ports.out.CustodyFragmentLifecyclePort
      custodyFragmentLifecyclePort(
          final CustodyFragmentPort custodyFragmentPort,
          final CustodyFragmentPayloadPort custodyFragmentPayloadPort,
          final org.springframework.beans.factory.ObjectProvider<
                  es.ual.node.negotiation.application.NegotiationService>
              negotiationServiceProvider) {
    // NegotiationService inyectado opcionalmente. Su disponibilidad depende del
    // feature flag node.features.negotiation-enabled. Sin él, decommissionCustody hace solo el
    // drop físico sin cancelar el agreement (cancel sería no-op porque no hay state machine).
    return new CustodyFragmentLifecycleAdapter(
        custodyFragmentPort,
        custodyFragmentPayloadPort,
        negotiationServiceProvider.getIfAvailable());
  }

  /** Provides custody liveness service. */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public CustodyLivenessService custodyLivenessService(
      final CustodyProbeSessionPort sessionPort,
      final CustodyFragmentInterestPort custodyFragmentInterestPort,
      final CustodyFragmentInventoryPort custodyFragmentInventoryPort,
      final CustodyEscalationPort custodyEscalationPort,
      final RemoteCustodyProbeClientPort remoteProbeClientPort,
      final CustodyLivenessProperties properties,
      final NodeIdentityContext nodeIdentityContext,
      final Clock clock,
      final CustodyLivenessObservabilityService custodyLivenessObservabilityService,
      final io.micrometer.observation.ObservationRegistry observationRegistry,
      final es.ual.node.custodyliveness.ports.out.CustodyFragmentLifecyclePort
          custodyFragmentLifecyclePort) {
    return new CustodyLivenessService(
        sessionPort,
        custodyFragmentInterestPort,
        custodyFragmentInventoryPort,
        custodyEscalationPort,
        remoteProbeClientPort,
        properties,
        nodeIdentityContext,
        clock,
        custodyLivenessObservabilityService,
        observationRegistry,
        custodyFragmentLifecyclePort);
  }

  /** Provides scheduled worker for custody liveness. */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public CustodyLivenessWorker custodyLivenessWorker(final CustodyLivenessService service) {
    return new CustodyLivenessWorker(service);
  }

  /**
   * Scheduled worker que dispara escalation cuando custody fragments expiran TTL sin probe activo
   * (cluster down / origen permanently unreachable).
   */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public es.ual.node.custodyliveness.application.CustodyExpiryEscalationWorker
      custodyExpiryEscalationWorker(
          final CustodyLivenessService service,
          final io.micrometer.observation.ObservationRegistry observationRegistry) {
    return new es.ual.node.custodyliveness.application.CustodyExpiryEscalationWorker(
        service, observationRegistry);
  }

  /**
   * Probe-bootstrap adapter that auto-schedules an outbound probe session against the origin the
   * first time this node accepts a custody fragment from it. Idempotent, coalesces multiple
   * fragments from the same upload into a single probe session.
   */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public CustodyEventNotifierPort custodyEventNotifierPort(
      final CustodyLivenessService custodyLivenessService) {
    return new CustodyLivenessProbeBootstrapAdapter(custodyLivenessService);
  }

  // ---------- custodian-initiated probe + origin inverse probe ----------

  /** in-memory port para origin_custodian_health. */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public OriginCustodianHealthPort originCustodianHealthPort() {
    return new InMemoryOriginCustodianHealthPort();
  }

  /** signed HTTP client del custodian al origen (POST /ops/custody-liveness/keep-list-request). */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public RemoteOriginKeepListClientPort remoteOriginKeepListClientPort(
      final NodeIdentityContext nodeIdentityContext,
      final CustodyLivenessProperties livenessProperties,
      final ObjectMapper objectMapper,
      @Value("${distributed.security.default-signature-algorithm:SHA256withECDSA}")
          final String signatureAlgorithm) {
    return new SignedHttpRemoteOriginKeepListClient(
        nodeIdentityContext, livenessProperties, objectMapper, signatureAlgorithm);
  }

  /** servicio del origen para handle inbound keep-list-request del custodian. */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public OriginInboundKeepListService originInboundKeepListService(
      final FragmentPlacementPort placementPort,
      final OriginCustodianHealthPort custodianHealthPort,
      final Clock clock) {
    return new OriginInboundKeepListService(placementPort, custodianHealthPort, clock);
  }

  /** servicio del custodian para iniciar probe outbound. */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public CustodianOutboundKeepListService custodianOutboundKeepListService(
      final CustodyFragmentInventoryPort inventoryPort,
      final CustodyFragmentLifecyclePort lifecyclePort,
      final RemoteOriginKeepListClientPort keepListClient,
      final CustodyLivenessProperties livenessProperties,
      final Clock clock) {
    return new CustodianOutboundKeepListService(
        inventoryPort, lifecyclePort, keepListClient, livenessProperties, clock);
  }

  /** scheduled worker custodian-side. */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public CustodianProbeWorker custodianProbeWorker(final CustodianOutboundKeepListService service) {
    return new CustodianProbeWorker(service);
  }

  /** servicio del origen para probe inverso a custodians silentes. */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public OriginInverseProbeService originInverseProbeService(
      final FragmentPlacementPort placementPort,
      final OriginCustodianHealthPort custodianHealthPort,
      final RemoteCustodyInventoryClientPort inventoryClient,
      final CustodyLivenessProperties livenessProperties,
      final NodeIdentityContext nodeIdentityContext,
      final Clock clock) {
    return new OriginInverseProbeService(
        placementPort,
        custodianHealthPort,
        inventoryClient,
        livenessProperties,
        nodeIdentityContext.nodeId(),
        clock);
  }

  /** scheduled worker origen-side. */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public OriginInverseProbeWorker originInverseProbeWorker(
      final OriginInverseProbeService service) {
    return new OriginInverseProbeWorker(service);
  }
}
