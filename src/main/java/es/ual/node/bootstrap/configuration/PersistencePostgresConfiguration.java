package es.ual.node.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ual.node.custodyliveness.ports.out.CustodyProbeSessionPort;
import es.ual.node.discovery.application.DiscoveryCandidateDirectoryProperties;
import es.ual.node.discovery.ports.out.DiscoveryCandidateDirectoryPort;
import es.ual.node.discovery.ports.out.DiscoveryRetryQueuePort;
import es.ual.node.filesystem.ports.out.FileManifestPort;
import es.ual.node.filesystem.ports.out.FileUploadSessionPort;
import es.ual.node.filesystem.ports.out.FragmentPlacementPort;
import es.ual.node.filesystem.ports.out.FsEntryPort;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPayloadPort;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPort;
import es.ual.node.identitysecurity.ports.out.NonceStore;
import es.ual.node.negotiation.application.CapacityProperties;
import es.ual.node.negotiation.ports.out.AgreementRepository;
import es.ual.node.negotiation.ports.out.CapacityPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresAgreementRepository;
import es.ual.node.persistence.adapters.out.postgres.PostgresCapacityPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresCustodiedFileManifestPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresCustodyFragmentPayloadPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresCustodyFragmentPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresCustodyProbeSessionPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresDiscoveryCandidateDirectoryPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresDiscoveryRetryQueuePort;
import es.ual.node.persistence.adapters.out.postgres.PostgresFileManifestPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresFileUploadSessionPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresFragmentPlacementPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresFsEntryPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresNonceStore;
import es.ual.node.persistence.adapters.out.postgres.PostgresRecoveryOrphanFragmentPayloadPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresRecoveryOrphanFragmentPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresRegistrationCodePort;
import es.ual.node.persistence.adapters.out.postgres.PostgresUserAccountPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresUserQuotaPort;
import es.ual.node.persistence.adapters.out.postgres.PostgresUserSessionPort;
import es.ual.node.persistence.jpa.CapacityCounterJpaRepository;
import es.ual.node.persistence.jpa.CapacityReservationJpaRepository;
import es.ual.node.persistence.jpa.ClientFileManifestJpaRepository;
import es.ual.node.persistence.jpa.ClientFragmentPlacementJpaRepository;
import es.ual.node.persistence.jpa.CustodyFragmentJpaRepository;
import es.ual.node.persistence.jpa.CustodyFragmentPayloadJpaRepository;
import es.ual.node.persistence.jpa.CustodyProbeSessionJpaRepository;
import es.ual.node.persistence.jpa.DiscoveryCandidateJpaRepository;
import es.ual.node.persistence.jpa.DiscoveryRetryRequestJpaRepository;
import es.ual.node.persistence.jpa.FileUploadSessionJpaRepository;
import es.ual.node.persistence.jpa.FsEntryJpaRepository;
import es.ual.node.persistence.jpa.NegotiationAgreementJpaRepository;
import es.ual.node.persistence.jpa.NonceJpaRepository;
import es.ual.node.persistence.jpa.RecoveryOrphanFragmentJpaRepository;
import es.ual.node.persistence.jpa.RecoveryOrphanFragmentPayloadJpaRepository;
import es.ual.node.persistence.jpa.RegistrationCodeJpaRepository;
import es.ual.node.persistence.jpa.UserAccountJpaRepository;
import es.ual.node.persistence.jpa.UserSessionJpaRepository;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPayloadPort;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPort;
import es.ual.node.userregistration.ports.out.RegistrationCodePort;
import es.ual.node.userregistration.ports.out.UserAccountPort;
import es.ual.node.userregistration.ports.out.UserQuotaPort;
import es.ual.node.userregistration.ports.out.UserSessionPort;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** PostgreSQL persistence wiring for outbound ports. */
@Configuration
@ConditionalOnProperty(prefix = "node.persistence", name = "mode", havingValue = "postgres")
public class PersistencePostgresConfiguration {

  /**
   * Creates PostgreSQL custody probe session adapter.
   *
   * @param repository JPA repository
   * @return custody probe session port
   */
  @Bean
  public CustodyProbeSessionPort custodyProbeSessionPort(
      final CustodyProbeSessionJpaRepository repository) {
    return new PostgresCustodyProbeSessionPort(repository);
  }

  /**
   * Creates PostgreSQL agreement repository adapter.
   *
   * @param repository JPA repository
   * @param objectMapper object mapper
   * @param clock clock
   * @return agreement repository port
   */
  @Bean
  public AgreementRepository agreementRepository(
      final NegotiationAgreementJpaRepository repository, final Clock clock) {
    return new PostgresAgreementRepository(repository, clock);
  }

  /**
   * Creates PostgreSQL capacity ledger adapter.
   *
   * @param reservationRepository capacity reservation repository
   * @param counterRepository capacity counter repository
   * @param capacityProperties capacity properties
   * @param clock clock
   * @return capacity port
   */
  @Bean
  public CapacityPort capacityPort(
      final CapacityReservationJpaRepository reservationRepository,
      final CapacityCounterJpaRepository counterRepository,
      final CapacityProperties capacityProperties,
      final Clock clock) {
    return new PostgresCapacityPort(
        reservationRepository, counterRepository, capacityProperties.getMaxBytes(), clock);
  }

  /**
   * Creates PostgreSQL nonce store adapter.
   *
   * @param repository JPA repository
   * @param clock clock
   * @return nonce store port
   */
  @Bean
  public NonceStore nonceStore(final NonceJpaRepository repository, final Clock clock) {
    return new PostgresNonceStore(repository, clock);
  }

  /** PostgreSQL custody-domain fragment metadata adapter. */
  @Bean
  public CustodyFragmentPort custodyFragmentPort(final CustodyFragmentJpaRepository repository) {
    return new PostgresCustodyFragmentPort(repository);
  }

  /** PostgreSQL custody-domain fragment payload adapter. */
  @Bean
  public CustodyFragmentPayloadPort custodyFragmentPayloadPort(
      final CustodyFragmentPayloadJpaRepository repository) {
    return new PostgresCustodyFragmentPayloadPort(repository);
  }

  /** PostgreSQL recovery-domain orphan fragment metadata adapter. */
  @Bean
  public RecoveryOrphanFragmentPort recoveryOrphanFragmentPort(
      final RecoveryOrphanFragmentJpaRepository repository) {
    return new PostgresRecoveryOrphanFragmentPort(repository);
  }

  /** PostgreSQL recovery-domain orphan fragment payload adapter. */
  @Bean
  public RecoveryOrphanFragmentPayloadPort recoveryOrphanFragmentPayloadPort(
      final RecoveryOrphanFragmentPayloadJpaRepository repository) {
    return new PostgresRecoveryOrphanFragmentPayloadPort(repository);
  }

  /**
   * Creates PostgreSQL custodied FileManifest adapter.
   *
   * @param repository JPA repository
   * @param objectMapper Jackson object mapper for fragmentHashes JSON
   * @return custodied file manifest port
   */
  @Bean
  public es.ual.node.recovery.ports.out.CustodiedFileManifestPort custodiedFileManifestPort(
      final es.ual.node.persistence.jpa.RecoveryFileManifestJpaRepository repository,
      final com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    return new PostgresCustodiedFileManifestPort(repository, objectMapper);
  }

  /**
   * Creates PostgreSQL user account adapter.
   *
   * @param repository JPA repository
   * @return user account port
   */
  @Bean
  public UserAccountPort userAccountPort(final UserAccountJpaRepository repository) {
    return new PostgresUserAccountPort(repository);
  }

  /**
   * Creates PostgreSQL user quota adapter.
   *
   * @param repository JPA repository (shares the user_account table with the account adapter)
   * @return user quota port
   */
  @Bean
  public UserQuotaPort userQuotaPort(final UserAccountJpaRepository repository) {
    return new PostgresUserQuotaPort(repository);
  }

  /**
   * Creates PostgreSQL client-side file manifest adapter.
   *
   * @param repository JPA repository del manifest
   * @param blockRepository JPA repository de los bloques
   * @param objectMapper object mapper para los hashes RS por bloque
   * @param clock system clock
   * @return file manifest port
   */
  @Bean
  public FileManifestPort fileManifestPort(
      final ClientFileManifestJpaRepository repository,
      final es.ual.node.persistence.jpa.ClientFileManifestBlockJpaRepository blockRepository,
      final ObjectMapper objectMapper,
      final Clock clock) {
    return new PostgresFileManifestPort(repository, blockRepository, objectMapper, clock);
  }

  /**
   * Creates PostgreSQL client-side fragment placement adapter.
   *
   * @param repository JPA repository
   * @return fragment placement port
   */
  @Bean
  public FragmentPlacementPort fragmentPlacementPort(
      final ClientFragmentPlacementJpaRepository repository) {
    return new PostgresFragmentPlacementPort(repository);
  }

  /**
   * Creates PostgreSQL registration code adapter.
   *
   * @param repository JPA repository
   * @return registration code port
   */
  @Bean
  public RegistrationCodePort registrationCodePort(final RegistrationCodeJpaRepository repository) {
    return new PostgresRegistrationCodePort(repository);
  }

  /**
   * Creates PostgreSQL user session adapter.
   *
   * @param repository JPA repository
   * @return user session port
   */
  @Bean
  public UserSessionPort userSessionPort(final UserSessionJpaRepository repository) {
    return new PostgresUserSessionPort(repository);
  }

  /**
   * Creates PostgreSQL filesystem metadata adapter.
   *
   * @param repository JPA repository
   * @return filesystem entry port
   */
  @Bean
  public FsEntryPort fsEntryPort(final FsEntryJpaRepository repository) {
    return new PostgresFsEntryPort(repository);
  }

  /**
   * Creates PostgreSQL upload session adapter.
   *
   * @param repository JPA repository
   * @return upload session port
   */
  @Bean
  public FileUploadSessionPort fileUploadSessionPort(
      final FileUploadSessionJpaRepository repository) {
    return new PostgresFileUploadSessionPort(repository);
  }

  /**
   * Creates PostgreSQL discovery candidate directory adapter.
   *
   * @param repository JPA repository
   * @param objectMapper object mapper
   * @param properties discovery directory properties
   * @param clock clock
   * @return discovery candidate directory port
   */
  @Bean
  public DiscoveryCandidateDirectoryPort discoveryCandidateDirectoryPort(
      final DiscoveryCandidateJpaRepository repository,
      final ObjectMapper objectMapper,
      final DiscoveryCandidateDirectoryProperties properties,
      final Clock clock) {
    return new PostgresDiscoveryCandidateDirectoryPort(repository, objectMapper, properties, clock);
  }

  /**
   * Creates PostgreSQL discovery retry queue adapter.
   *
   * @param repository JPA repository
   * @param objectMapper object mapper
   * @return discovery retry queue port
   */
  @Bean
  public DiscoveryRetryQueuePort discoveryRetryQueuePort(
      final DiscoveryRetryRequestJpaRepository repository, final ObjectMapper objectMapper) {
    return new PostgresDiscoveryRetryQueuePort(repository, objectMapper);
  }

  /**
   * PostgreSQL adapter para origin_custodian_health: tracking del último probe entrante por
   * custodian. Activo cuando {@code node.persistence.mode=postgres} y custody-liveness está
   * enabled. La variante in-memory vive en {@link CustodyLivenessModuleConfiguration} bajo el flag
   * complementario.
   */
  @Bean
  @ConditionalOnProperty(prefix = "node.persistence", name = "mode", havingValue = "postgres")
  @ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
  public es.ual.node.custodyliveness.ports.out.OriginCustodianHealthPort
      postgresOriginCustodianHealthPort(
          final es.ual.node.persistence.jpa.OriginCustodianHealthJpaRepository repository) {
    return new es.ual.node.persistence.adapters.out.postgres.PostgresOriginCustodianHealthPort(
        repository);
  }
}
