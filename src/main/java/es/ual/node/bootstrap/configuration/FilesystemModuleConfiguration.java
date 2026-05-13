package es.ual.node.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.filesystem.adapters.out.http.SignedHttpRemoteFileManifestStoreClient;
import es.ual.node.filesystem.adapters.out.http.SignedHttpRemoteFragmentDistributionClient;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFileManifestPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFileUploadSessionPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFragmentPlacementPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFsEntryPort;
import es.ual.node.filesystem.adapters.out.memory.LocalDiskFsFileContentPort;
import es.ual.node.filesystem.adapters.out.memory.LocalDiskFsUploadStagingPort;
import es.ual.node.filesystem.application.FileContentDistributionService;
import es.ual.node.filesystem.application.FileContentService;
import es.ual.node.filesystem.application.FileSystemService;
import es.ual.node.filesystem.application.FileUploadSessionService;
import es.ual.node.filesystem.ports.out.FileManifestPort;
import es.ual.node.filesystem.ports.out.FileUploadSessionPort;
import es.ual.node.filesystem.ports.out.FragmentPlacementPort;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.filesystem.ports.out.FsFileContentPort;
import es.ual.node.filesystem.ports.out.FsUploadStagingPort;
import es.ual.node.filesystem.ports.out.RemoteFragmentDistributionClientPort;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.sync.application.SyncEventService;
import java.nio.file.Paths;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Filesystem module wiring. */
@Configuration
public class FilesystemModuleConfiguration {

  /**
   * Provides in-memory filesystem persistence.
   *
   * @return filesystem persistence port
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public FsEntryPort fsEntryPort() {
    return new InMemoryFsEntryPort();
  }

  /**
   * Provides in-memory upload session persistence.
   *
   * @return upload session port
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public FileUploadSessionPort fileUploadSessionPort() {
    return new InMemoryFileUploadSessionPort();
  }

  /**
   * Provides in-memory file manifest persistence.
   *
   * @return file manifest port
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public FileManifestPort fileManifestPort() {
    return new InMemoryFileManifestPort();
  }

  /**
   * Provides in-memory fragment placement persistence.
   *
   * @return fragment placement port
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.persistence",
      name = "mode",
      havingValue = "memory",
      matchIfMissing = true)
  public FragmentPlacementPort fragmentPlacementPort() {
    return new InMemoryFragmentPlacementPort();
  }

  /**
   * Provides the signed HTTP client used by the origin to distribute and recover fragments via
   * {@code /custody/fragments}.
   *
   * @param nodeIdentityContext local node identity (signs requests)
   * @return remote fragment distribution client
   */
  @Bean
  public RemoteFragmentDistributionClientPort remoteFragmentDistributionClientPort(
      final NodeIdentityContext nodeIdentityContext) {
    return new SignedHttpRemoteFragmentDistributionClient(nodeIdentityContext, "SHA256withECDSA");
  }

  /**
   * Provides the signed HTTP client used by the origin to replicate the file manifest to its tutor
   * right after distribution. Active when {@code node.filesystem.distribution.enabled=true}; the
   * bean is consumed via {@link org.springframework.beans.factory.ObjectProvider} so test/legacy
   * wirings without distribution stay unaffected.
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.filesystem.distribution",
      name = "enabled",
      havingValue = "true")
  public es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort remoteFileManifestStorePort(
      final NodeIdentityContext nodeIdentityContext, final ObjectMapper objectMapper) {
    return new SignedHttpRemoteFileManifestStoreClient(
        nodeIdentityContext, objectMapper, "SHA256withECDSA");
  }

  /**
   * Provides the file content distribution orchestrator. Active when {@code
   * node.filesystem.distribution.enabled=true}; clients of {@link FileContentService} use it to
   * fragment via Reed-Solomon, distribute over the cluster and reconstruct on download.
   *
   * @param encoderPort RS encoder
   * @param decoderPort RS decoder
   * @param integrityVerifierPort RS integrity verifier
   * @param fileManifestPort manifest persistence at the origin
   * @param fragmentPlacementPort placement persistence at the origin
   * @param remoteFragmentClient signed HTTP client to {@code /custody/fragments}
   * @param userQuotaPort quota tracker
   * @param topology topology properties (provides custodian base URLs)
   * @param clock system clock
   * @param schemeN RS n (default 3)
   * @param schemeK RS k (default 2)
   * @param symbolSize RS symbol size in bytes (default 4096)
   * @param maxBytes maximum upload size in bytes (default 10 MB)
   * @return distribution orchestrator
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "node.filesystem.distribution",
      name = "enabled",
      havingValue = "true")
  public FileContentDistributionService fileContentDistributionService(
      @Qualifier("rsEncoderPort") final es.ual.node.reedsolomon.ports.out.RsEncoderPort encoderPort,
      @Qualifier("rsDecoderPort") final es.ual.node.reedsolomon.ports.out.RsDecoderPort decoderPort,
      final es.ual.node.reedsolomon.ports.out.RsIntegrityVerifierPort integrityVerifierPort,
      final FileManifestPort fileManifestPort,
      final FragmentPlacementPort fragmentPlacementPort,
      final RemoteFragmentDistributionClientPort remoteFragmentClient,
      final es.ual.node.userregistration.ports.out.UserQuotaPort userQuotaPort,
      final NodeTopologyProperties topology,
      final Clock clock,
      @Value("${node.reedsolomon.default-scheme.n:3}") final int schemeN,
      @Value("${node.reedsolomon.default-scheme.k:2}") final int schemeK,
      @Value("${node.reedsolomon.default-scheme.symbol-size:4096}") final int symbolSize,
      @Value("${node.filesystem.distribution.max-bytes:-1}") final long maxBytes,
      @Value("${node.reedsolomon.block-size-bytes:4194304}") final int blockSizeBytes,
      final org.springframework.beans.factory.ObjectProvider<
              es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort>
          remoteFileManifestStorePort,
      final FsEntryPort fsEntryPort,
      final org.springframework.beans.factory.ObjectProvider<
              es.ual.node.discovery.ports.out.RemoteDiscoveryQueryClientPort>
          remoteDiscoveryQueryClientProvider,
      final org.springframework.beans.factory.ObjectProvider<
              es.ual.node.discovery.application.DiscoveryQueryCache>
          discoveryQueryCacheProvider,
      final NodeIdentityContext nodeIdentityContext,
      @Value("${node.failure-domain:}") final String localFailureDomain,
      @Value("${node.discovery.requested-bucket:1024}") final long defaultRequestedBucket,
      @Value("${node.discovery.ratio:1.0}") final double defaultRatio,
      @Value("${node.discovery.distribution-plan:}") final String defaultDistributionPlan,
      @Value("${node.discovery.max-retries:3}") final int maxDiscoveryRetries,
      @Value("${node.discovery.allow-self-candidate:true}") final boolean allowSelfCandidate) {
    // El origen ya no usa un DiscoveryService LOCAL, siempre consulta
    // supernodos remotos vía RemoteDiscoveryQueryClientPort + topology.discoverySupernodes.
    // Cuando ese cliente o la lista están ausentes (legacy/tests/discovery off), el
    // orchestrator cae al fallback estático custodianBaseUrls (lista hardcoded en config).
    final es.ual.node.discovery.ports.out.RemoteDiscoveryQueryClientPort remoteQueryClient =
        remoteDiscoveryQueryClientProvider.getIfAvailable();
    final es.ual.node.discovery.application.DiscoveryQueryCache discoveryQueryCache =
        discoveryQueryCacheProvider.getIfAvailable();
    final java.util.List<String> discoverySupernodes =
        topology.getDiscoverySupernodes() == null
            ? java.util.List.of()
            : topology.getDiscoverySupernodes();
    final FileContentDistributionService.DiscoveryWiring discoveryWiring =
        (remoteQueryClient == null || discoverySupernodes.isEmpty())
            ? null
            : new FileContentDistributionService.DiscoveryWiring(
                remoteQueryClient,
                discoverySupernodes,
                discoveryQueryCache,
                nodeIdentityContext.nodeId(),
                localFailureDomain,
                defaultRequestedBucket,
                defaultRatio,
                defaultDistributionPlan,
                maxDiscoveryRetries,
                allowSelfCandidate);
    return new FileContentDistributionService(
        encoderPort,
        decoderPort,
        integrityVerifierPort,
        fileManifestPort,
        fragmentPlacementPort,
        remoteFragmentClient,
        userQuotaPort,
        clock,
        new es.ual.node.reedsolomon.domain.RsScheme(schemeN, schemeK, symbolSize),
        maxBytes,
        blockSizeBytes,
        topology.getDiscoverySupernodes(),
        remoteFileManifestStorePort.getIfAvailable(),
        topology.getTutorBaseUrl(),
        fsEntryPort,
        discoveryWiring);
  }

  /**
   * Provides filesystem service.
   *
   * @param fsEntryPort filesystem persistence
   * @param clock clock
   * @param manifestPort manifest port para hard-delete del recovery manifest cuando se borra un
   *     fs_entry. Inyectado opcionalmente, si la app se configura sin recovery (modo cliente puro)
   *     el bean falta y delete() simplemente no purga manifest, sin romperse.
   * @param distributionService Reed-Solomon orchestrator. Inyectado opcionalmente, si {@code
   *     node.filesystem.distribution.enabled=false} el bean no existe y delete() no libera cuota
   *     (modo blob local).
   * @return filesystem service
   */
  @Bean
  public FileSystemService fileSystemService(
      final FsEntryPort fsEntryPort,
      final Clock clock,
      final org.springframework.beans.factory.ObjectProvider<
              es.ual.node.recovery.ports.out.CustodiedFileManifestPort>
          manifestPort,
      final org.springframework.beans.factory.ObjectProvider<FileContentDistributionService>
          distributionService,
      final org.springframework.beans.factory.ObjectProvider<
              es.ual.node.filesystem.ports.out.RemoteFileManifestStorePort>
          remoteFileManifestStorePort,
      final NodeTopologyProperties topology) {
    return new FileSystemService(
        fsEntryPort,
        clock,
        manifestPort.getIfAvailable(),
        distributionService.getIfAvailable(),
        remoteFileManifestStorePort.getIfAvailable(),
        topology.getTutorBaseUrl());
  }

  /**
   * Provides local-disk file content persistence.
   *
   * @param baseDirectory base directory for client file content storage
   * @return file content persistence port
   */
  @Bean
  public FsFileContentPort fsFileContentPort(
      @Value("${node.client-files.base-directory:./data/client-files}")
          final String baseDirectory) {
    return new LocalDiskFsFileContentPort(Paths.get(baseDirectory));
  }

  /**
   * Provides local-disk upload staging persistence.
   *
   * @param stagingDirectory staging directory path
   * @return upload staging port
   */
  @Bean
  public FsUploadStagingPort fsUploadStagingPort(
      @Value("${node.client-files.staging-directory:./data/client-files-staging}")
          final String stagingDirectory) {
    return new LocalDiskFsUploadStagingPort(Paths.get(stagingDirectory));
  }

  /**
   * Provides file content service.
   *
   * @param fsEntryPort filesystem metadata persistence
   * @param fsFileContentPort filesystem content persistence (legacy local-blob fallback)
   * @param distributionService Reed-Solomon orchestrator. Inyectado opcionalmente, si {@code
   *     node.filesystem.distribution.enabled=false} el bean no existe y la subida/descarga usa el
   *     adapter de blob local.
   * @return file content service
   */
  @Bean
  public FileContentService fileContentService(
      final FsEntryPort fsEntryPort,
      final FsFileContentPort fsFileContentPort,
      final org.springframework.beans.factory.ObjectProvider<FileContentDistributionService>
          distributionService) {
    return new FileContentService(
        fsEntryPort, fsFileContentPort, distributionService.getIfAvailable());
  }

  /** Provides resumable upload session service. */
  @Bean
  public FileUploadSessionService fileUploadSessionService(
      final FsEntryPort fsEntryPort,
      final FsFileContentPort fsFileContentPort,
      final FileUploadSessionPort fileUploadSessionPort,
      final FsUploadStagingPort fsUploadStagingPort,
      final Clock clock,
      final org.springframework.beans.factory.ObjectProvider<FileContentDistributionService>
          distributionService) {
    return new FileUploadSessionService(
        fsEntryPort,
        fsFileContentPort,
        fileUploadSessionPort,
        fsUploadStagingPort,
        clock,
        distributionService.getIfAvailable());
  }

  /**
   * Provides in-memory sync event hub.
   *
   * @return sync event service
   */
  @Bean
  public SyncEventService syncEventService() {
    return new SyncEventService();
  }
}
