package es.ual.node.bootstrap.configuration;

import es.ual.node.filesystem.application.FileContentDistributionService;
import es.ual.node.filesystem.ports.out.FileManifestPort;
import es.ual.node.filesystem.ports.out.FragmentPlacementPort;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.recovery.adapters.out.memory.InMemoryCustodiedFileManifestPort;
import es.ual.node.recovery.adapters.out.memory.InMemoryRecoveryOrphanFragmentPayloadPort;
import es.ual.node.recovery.adapters.out.memory.InMemoryRecoveryOrphanFragmentPort;
import es.ual.node.recovery.application.FileIntegrityRiskOrchestrator;
import es.ual.node.recovery.application.FileIntegrityRiskWorker;
import es.ual.node.recovery.application.RecoveryConsistencyService;
import es.ual.node.recovery.application.RecoveryConsistencyWorker;
import es.ual.node.recovery.application.RecoveryObservabilityService;
import es.ual.node.recovery.application.RecoveryProperties;
import es.ual.node.recovery.application.TutorFileManifestCustodyService;
import es.ual.node.recovery.application.TutorRecoveryService;
import es.ual.node.recovery.ports.out.CustodiedFileManifestPort;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPayloadPort;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPort;
import es.ual.node.reedsolomon.ports.out.RsDecoderPort;
import es.ual.node.reedsolomon.ports.out.RsIntegrityVerifierPort;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration for tutor recovery module. */
@Configuration
public class RecoveryModuleConfiguration {

  /**
   * Provides in-memory recovery custody port.
   *
   * @return recovery custody port
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public RecoveryOrphanFragmentPort recoveryOrphanFragmentPort() {
    return new InMemoryRecoveryOrphanFragmentPort();
  }

  /**
   * Provides in-memory payload port when persistence mode is memory.
   *
   * @return payload port
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public RecoveryOrphanFragmentPayloadPort recoveryOrphanFragmentPayloadPort() {
    return new InMemoryRecoveryOrphanFragmentPayloadPort();
  }

  /** Provides recovery observability counters. */
  @Bean
  public RecoveryObservabilityService recoveryObservabilityService() {
    return new RecoveryObservabilityService();
  }

  /**
   * Provides tutor recovery service.
   *
   * @param nodeTopologyProperties topology properties
   * @param recoveryOrphanFragmentPort recovery custody persistence port
   * @param recoveryOrphanFragmentPayloadPort recovery payload persistence port
   * @param rsDecoderPort RS decoder port
   * @param rsIntegrityVerifierPort RS integrity verifier port
   * @param clock clock bean
   * @param recoveryObservabilityService recovery observability service
   * @return recovery service
   */
  @Bean
  public TutorRecoveryService tutorRecoveryService(
      final NodeTopologyProperties nodeTopologyProperties,
      final RecoveryOrphanFragmentPort recoveryOrphanFragmentPort,
      final RecoveryOrphanFragmentPayloadPort recoveryOrphanFragmentPayloadPort,
      final RsDecoderPort rsDecoderPort,
      final RsIntegrityVerifierPort rsIntegrityVerifierPort,
      final Clock clock,
      final RecoveryObservabilityService recoveryObservabilityService) {
    return new TutorRecoveryService(
        nodeTopologyProperties,
        recoveryOrphanFragmentPort,
        recoveryOrphanFragmentPayloadPort,
        rsDecoderPort,
        rsIntegrityVerifierPort,
        clock,
        recoveryObservabilityService);
  }

  /**
   * Provides in-memory custodied FileManifest port.
   *
   * @return port
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public CustodiedFileManifestPort custodiedFileManifestPort() {
    return new InMemoryCustodiedFileManifestPort();
  }

  /**
   * Provides tutor file manifest custody service. Supervivencia mediante {@code
   * TutorManifestKeepListWorker}.
   *
   * @param topology topology properties (whitelist of accepted requester keys)
   * @param port custody port
   * @param clock clock
   * @return service
   */
  @Bean
  public TutorFileManifestCustodyService tutorFileManifestCustodyService(
      final NodeTopologyProperties topology,
      final CustodiedFileManifestPort port,
      final Clock clock) {
    return new TutorFileManifestCustodyService(topology, port, clock);
  }

  /** Provides recovery consistency maintenance service. */
  @Bean
  @ConditionalOnProperty(prefix = "node.features", name = "recovery-enabled", havingValue = "true")
  @ConditionalOnProperty(
      prefix = "node.recovery.consistency",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public RecoveryConsistencyService recoveryConsistencyService(
      final RecoveryOrphanFragmentPort recoveryOrphanFragmentPort,
      final RecoveryOrphanFragmentPayloadPort recoveryOrphanFragmentPayloadPort,
      final RecoveryProperties recoveryProperties,
      final RecoveryObservabilityService recoveryObservabilityService) {
    return new RecoveryConsistencyService(
        recoveryOrphanFragmentPort,
        recoveryOrphanFragmentPayloadPort,
        recoveryProperties,
        recoveryObservabilityService);
  }

  /** Provides scheduled worker for recovery consistency maintenance. */
  @Bean
  @ConditionalOnProperty(prefix = "node.features", name = "recovery-enabled", havingValue = "true")
  @ConditionalOnProperty(
      prefix = "node.recovery.consistency",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public RecoveryConsistencyWorker recoveryConsistencyWorker(
      final RecoveryConsistencyService recoveryConsistencyService) {
    return new RecoveryConsistencyWorker(recoveryConsistencyService);
  }

  // ---------- Manifest list client (consumed by bootstrap runner + tutor sync worker) ----------

  /**
   * Signed HTTP client que lista los manifests custodiados por el tutor para este nodo. Activo en
   * modo RESTORE (consumido por {@link es.ual.node.recovery.application.NodeFsRestoreService}) o
   * cuando custody-liveness está enabled (consumido por el {@code OriginTutorManifestSyncWorker}.
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
      "'${node.recovery.mode:NORMAL}' == 'RESTORE'" + " or ${node.custody-liveness.enabled:false}")
  public es.ual.node.recovery.ports.out.RemoteCustodiedManifestListClientPort
      remoteCustodiedManifestListClientPort(
          final es.ual.node.identitysecurity.application.NodeIdentityContext nodeIdentityContext,
          final NodeTopologyProperties topologyProperties,
          final com.fasterxml.jackson.databind.ObjectMapper objectMapper,
          final SecurityProperties securityProperties) {
    return new es.ual.node.recovery.adapters.out.http.SignedHttpRemoteCustodiedManifestListClient(
        nodeIdentityContext,
        topologyProperties,
        objectMapper,
        securityProperties.getDefaultSignatureAlgorithm());
  }

  // ---------- bootstrap runner para modo RESTORE ----------

  /**
   * Restore service que reconstruye el filesystem local desde manifests custodiados por el tutor
   * cuando el operador arranca el nodo en {@code node.recovery.mode=RESTORE}. Solo se registra en
   * ese modo para no inyectar dependencias innecesarias en arranque NORMAL.
   */
  /**
   * Signed HTTP client para POST /recovery/fragments/reconstruct en el tutor. Activo en modo
   * RESTORE; consumido por NodeFsRestoreService cuando strategy=BYTES_FROM_TUTOR.
   */
  @Bean
  @ConditionalOnProperty(prefix = "node.recovery", name = "mode", havingValue = "RESTORE")
  public es.ual.node.recovery.ports.out.RemoteRecoveryReconstructClientPort
      remoteRecoveryReconstructClientPort(
          final es.ual.node.identitysecurity.application.NodeIdentityContext nodeIdentityContext,
          final com.fasterxml.jackson.databind.ObjectMapper objectMapper,
          final SecurityProperties securityProperties) {
    return new es.ual.node.recovery.adapters.out.http.SignedHttpRemoteRecoveryReconstructClient(
        nodeIdentityContext, objectMapper, securityProperties.getDefaultSignatureAlgorithm());
  }

  /**
   * Signed HTTP client para POST /recovery/orphan-fragments/{fragmentId}/ack en el tutor. Activo en
   * modo RESTORE; consumido por NodeFsRestoreService tras un re-upload exitoso para limpiar los
   * orphan fragments del tutor que referencian al fileId superado.
   */
  @Bean
  @ConditionalOnProperty(prefix = "node.recovery", name = "mode", havingValue = "RESTORE")
  public es.ual.node.recovery.ports.out.RemoteOrphanFragmentAckClientPort
      remoteOrphanFragmentAckClientPort(
          final es.ual.node.identitysecurity.application.NodeIdentityContext nodeIdentityContext,
          final SecurityProperties securityProperties) {
    return new es.ual.node.recovery.adapters.out.http.SignedHttpRemoteOrphanFragmentAckClient(
        nodeIdentityContext, securityProperties.getDefaultSignatureAlgorithm());
  }

  /**
   * El restore en strategy {@code BYTES_FROM_TUTOR} ya no persiste blob local. Tras reconstruir
   * bytes vía tutor, re-emite como subida estándar usando {@link
   * es.ual.node.recovery.ports.out.FileRecomposePort} (delegado a {@link
   * FileContentDistributionService}) y borra el manifest viejo del tutor vía {@link
   * es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort}. Cero {@code FsFileContentPort}
   * inyectado, el modelo "fragments-only nodes" se preserva tras restore.
   */
  @Bean
  @ConditionalOnProperty(prefix = "node.recovery", name = "mode", havingValue = "RESTORE")
  public es.ual.node.recovery.application.NodeFsRestoreService nodeFsRestoreService(
      final es.ual.node.recovery.ports.out.RemoteCustodiedManifestListClientPort manifestListClient,
      final es.ual.node.filesystem.ports.out.FsEntryPort fsEntryPort,
      final es.ual.node.filesystem.ports.out.FileManifestPort fileManifestPort,
      final es.ual.node.filesystem.ports.out.FragmentPlacementPort fragmentPlacementPort,
      final com.fasterxml.jackson.databind.ObjectMapper objectMapper,
      final Clock clock,
      final RecoveryProperties recoveryProperties,
      final es.ual.node.recovery.ports.out.RemoteRecoveryReconstructClientPort reconstructClient,
      final org.springframework.beans.factory.ObjectProvider<
              es.ual.node.recovery.ports.out.FileRecomposePort>
          fileRecomposePortProvider,
      final org.springframework.beans.factory.ObjectProvider<
              es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort>
          remoteFileManifestStorePortProvider,
      final org.springframework.beans.factory.ObjectProvider<
              es.ual.node.recovery.ports.out.RemoteOrphanFragmentAckClientPort>
          remoteOrphanFragmentAckClientPortProvider,
      final NodeTopologyProperties topology,
      final es.ual.node.identitysecurity.application.NodeIdentityContext nodeIdentityContext,
      final es.ual.node.custodyliveness.application.CustodyLivenessProperties livenessProperties) {
    final String selfNodeId = nodeIdentityContext.nodeId();
    final String selfBaseUrl =
        livenessProperties.getRemoteBaseUrls() == null
            ? null
            : livenessProperties.getRemoteBaseUrls().get(selfNodeId);
    return new es.ual.node.recovery.application.NodeFsRestoreService(
        manifestListClient,
        fsEntryPort,
        fileManifestPort,
        fragmentPlacementPort,
        objectMapper,
        clock,
        recoveryProperties.getRestoreStrategy(),
        reconstructClient,
        fileRecomposePortProvider.getIfAvailable(),
        remoteFileManifestStorePortProvider.getIfAvailable(),
        remoteOrphanFragmentAckClientPortProvider.getIfAvailable(),
        topology.getTutorBaseUrl(),
        selfNodeId,
        selfBaseUrl);
  }

  /**
   * Signed HTTP client para el endpoint inventory en peers custodios. Activo en modo RESTORE
   * (consumido por el worker) o cuando custody-liveness está habilitado (probe cycle).
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
      "'${node.recovery.mode:NORMAL}' == 'RESTORE'" + " or ${node.custody-liveness.enabled:false}")
  public es.ual.node.fragmentstorage.ports.out.RemoteCustodyInventoryClientPort
      remoteCustodyInventoryClientPort(
          final es.ual.node.identitysecurity.application.NodeIdentityContext nodeIdentityContext,
          final com.fasterxml.jackson.databind.ObjectMapper objectMapper,
          final SecurityProperties securityProperties) {
    return new es.ual.node.fragmentstorage.adapters.out.http.SignedHttpRemoteCustodyInventoryClient(
        nodeIdentityContext, objectMapper, securityProperties.getDefaultSignatureAlgorithm());
  }

  /**
   * Solo dispara el restore del catalog (manifest + placements + fs_entry) desde el tutor; el
   * {@code FileIntegrityRiskOrchestrator} toma el relevo periódicamente con política de risk score
   * agregado.
   */
  @Bean
  @ConditionalOnProperty(prefix = "node.recovery", name = "mode", havingValue = "RESTORE")
  public RecoveryBootstrapRunner recoveryBootstrapRunner(
      final es.ual.node.recovery.application.NodeFsRestoreService nodeFsRestoreService,
      final RecoveryProperties recoveryProperties) {
    return new RecoveryBootstrapRunner(nodeFsRestoreService, recoveryProperties);
  }

  // ---------- file integrity risk orchestrator ----------

  /**
   * Adapter del port FileRecomposePort que delega al servicio del filesystem module. Solo se wirea
   * si TANTO custody-liveness está enabled como {@code node.filesystem.distribution.enabled=true}
   * (el bean {@link FileContentDistributionService} existe). Sin distribution, no hay nada que
   * recomponer.
   */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
      FileContentDistributionService.class)
  public es.ual.node.recovery.ports.out.FileRecomposePort fileRecomposePort(
      final FileContentDistributionService distributionService) {
    return new es.ual.node.recovery.adapters.out.filesystem.FileContentDistributionRecomposeAdapter(
        distributionService);
  }

  /**
   * Orchestrator que evalúa risk score por archivo y dispara recompose total cuando es posible.
   * Requiere {@link es.ual.node.recovery.ports.out.FileRecomposePort} disponible (filesystem
   * distribution + custody-liveness enabled). Los eventos se materializan en counters Prometheus
   * (vía {@link RecoveryObservabilityService}) + logs estructurados; no se persisten en SQL.
   */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
      es.ual.node.recovery.ports.out.FileRecomposePort.class)
  public FileIntegrityRiskOrchestrator fileIntegrityRiskOrchestrator(
      final FragmentPlacementPort placementPort,
      final FileManifestPort manifestPort,
      final FsEntryPort fsEntryPort,
      final es.ual.node.recovery.ports.out.FileRecomposePort recomposePort,
      final RecoveryProperties recoveryProperties,
      final Clock clock,
      final RecoveryObservabilityService recoveryObservabilityService) {
    return new FileIntegrityRiskOrchestrator(
        placementPort,
        manifestPort,
        fsEntryPort,
        recomposePort,
        recoveryProperties,
        clock,
        recoveryObservabilityService);
  }

  /** scheduled worker que ejecuta el orchestrator periódicamente. */
  @Bean
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
      FileIntegrityRiskOrchestrator.class)
  public FileIntegrityRiskWorker fileIntegrityRiskWorker(
      final FileIntegrityRiskOrchestrator orchestrator) {
    return new FileIntegrityRiskWorker(orchestrator);
  }

  // ---------- tutor keep-list (whitelist invertida) ----------

  /** signed HTTP client que el tutor usa para preguntar al origen su keep-list. */
  @Bean
  @ConditionalOnProperty(prefix = "node.features", name = "recovery-enabled", havingValue = "true")
  public es.ual.node.recovery.ports.out.RemoteOriginKeepListClientPort
      tutorRemoteOriginManifestKeepListClient(
          final es.ual.node.identitysecurity.application.NodeIdentityContext nodeIdentityContext,
          final com.fasterxml.jackson.databind.ObjectMapper objectMapper,
          final SecurityProperties securityProperties) {
    return new es.ual.node.recovery.adapters.out.http.SignedHttpRemoteOriginKeepListClient(
        nodeIdentityContext, objectMapper, securityProperties.getDefaultSignatureAlgorithm());
  }

  /** tutor-side keep-list service. */
  @Bean
  @ConditionalOnProperty(prefix = "node.features", name = "recovery-enabled", havingValue = "true")
  public es.ual.node.recovery.application.TutorManifestKeepListService tutorManifestKeepListService(
      final es.ual.node.recovery.ports.out.CustodiedFileManifestPort port,
      final es.ual.node.recovery.ports.out.RemoteOriginKeepListClientPort keepListClient,
      final NodeTopologyProperties topology,
      final Clock clock) {
    return new es.ual.node.recovery.application.TutorManifestKeepListService(
        port, keepListClient, topology, clock);
  }

  /** tutor-side scheduled worker. */
  @Bean
  @ConditionalOnProperty(prefix = "node.features", name = "recovery-enabled", havingValue = "true")
  public es.ual.node.recovery.application.TutorManifestKeepListWorker tutorManifestKeepListWorker(
      final es.ual.node.recovery.application.TutorManifestKeepListService service) {
    return new es.ual.node.recovery.application.TutorManifestKeepListWorker(service);
  }

  // ---------- supervisado pregunta al tutor (endpoint inverso) ----------

  /** signed HTTP client que el origen usa para pedir inventory al tutor. */
  @Bean
  @ConditionalOnProperty(prefix = "node.features", name = "recovery-enabled", havingValue = "true")
  public es.ual.node.recovery.ports.out.RemoteTutorManifestInventoryClientPort
      remoteTutorManifestInventoryClientPort(
          final es.ual.node.identitysecurity.application.NodeIdentityContext nodeIdentityContext,
          final com.fasterxml.jackson.databind.ObjectMapper objectMapper,
          final SecurityProperties securityProperties) {
    return new es.ual.node.recovery.adapters.out.http.SignedHttpRemoteTutorManifestInventoryClient(
        nodeIdentityContext, objectMapper, securityProperties.getDefaultSignatureAlgorithm());
  }

  /**
   * origen-side sync service. Reuse {@link es.ual.node.filesystem.ports.out.FragmentPlacementPort}
   * to resolve placements for re-emit. Sólo se wirea cuando {@link
   * es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort} está disponible (distribution
   * enabled); sin él no hay forma de re-emitir manifests.
   */
  @Bean
  @ConditionalOnProperty(prefix = "node.features", name = "recovery-enabled", havingValue = "true")
  @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
      es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort.class)
  public es.ual.node.recovery.application.OriginTutorManifestSyncService
      originTutorManifestSyncService(
          final es.ual.node.filesystem.ports.out.FileManifestPort localManifestPort,
          final es.ual.node.recovery.ports.out.RemoteTutorManifestInventoryClientPort
              inventoryClient,
          final es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort manifestStorePort,
          final NodeTopologyProperties topology,
          final FragmentPlacementPort placementPort) {
    return new es.ual.node.recovery.application.OriginTutorManifestSyncService(
        localManifestPort,
        inventoryClient,
        manifestStorePort,
        topology,
        placementPort::findByFileId);
  }

  /** origen-side scheduled worker. */
  @Bean
  @ConditionalOnProperty(prefix = "node.features", name = "recovery-enabled", havingValue = "true")
  @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
      es.ual.node.recovery.application.OriginTutorManifestSyncService.class)
  public es.ual.node.recovery.application.OriginTutorManifestSyncWorker
      originTutorManifestSyncWorker(
          final es.ual.node.recovery.application.OriginTutorManifestSyncService service) {
    return new es.ual.node.recovery.application.OriginTutorManifestSyncWorker(service);
  }

  // ---------- orphan claim+ACK ----------

  /** orphan claim+ACK service. */
  @Bean
  @ConditionalOnProperty(prefix = "node.features", name = "recovery-enabled", havingValue = "true")
  public es.ual.node.recovery.application.RecoveryOrphanClaimService recoveryOrphanClaimService(
      final RecoveryOrphanFragmentPort orphanPort,
      final RecoveryOrphanFragmentPayloadPort payloadPort) {
    return new es.ual.node.recovery.application.RecoveryOrphanClaimService(orphanPort, payloadPort);
  }
}
