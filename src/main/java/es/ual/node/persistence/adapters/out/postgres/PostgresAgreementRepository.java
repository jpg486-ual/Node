package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.negotiation.domain.NegotiationAgreement;
import es.ual.node.negotiation.domain.NegotiationStatus;
import es.ual.node.negotiation.domain.TransferAuthorizationToken;
import es.ual.node.negotiation.domain.TransferMode;
import es.ual.node.negotiation.ports.out.AgreementRepository;
import es.ual.node.persistence.jpa.NegotiationAgreementJpaEntity;
import es.ual.node.persistence.jpa.NegotiationAgreementJpaRepository;
import java.time.Clock;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed agreement repository adapter. */
public class PostgresAgreementRepository implements AgreementRepository {

  private final NegotiationAgreementJpaRepository repository;
  private final Clock clock;

  /** Creates adapter. */
  public PostgresAgreementRepository(
      final NegotiationAgreementJpaRepository repository, final Clock clock) {
    if (repository == null || clock == null) {
      throw new IllegalArgumentException("Dependencies must not be null");
    }
    this.repository = repository;
    this.clock = clock;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional
  public NegotiationAgreement save(final NegotiationAgreement agreement) {
    if (agreement == null) {
      throw new IllegalArgumentException("agreement must not be null");
    }
    expirePending();
    NegotiationAgreementJpaEntity persisted = repository.save(toEntity(agreement));
    return toDomain(persisted);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional
  public Optional<NegotiationAgreement> findById(final String agreementId) {
    if (agreementId == null || agreementId.isBlank()) {
      return Optional.empty();
    }
    expirePending();
    return repository.findById(agreementId.trim()).map(this::toDomain);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional
  public int countPending() {
    expirePending();
    return repository.countByStatus(NegotiationStatus.PENDING.name());
  }

  private void expirePending() {
    repository.expirePending(clock.instant());
  }

  private NegotiationAgreementJpaEntity toEntity(final NegotiationAgreement agreement) {
    NegotiationAgreementJpaEntity entity = new NegotiationAgreementJpaEntity();
    entity.setAgreementId(agreement.agreementId());
    entity.setRequesterNodeId(agreement.requesterNodeId());
    entity.setTargetNodeId(agreement.targetNodeId());
    entity.setStatus(agreement.status().name());
    entity.setTransferMode(agreement.transferMode().name());
    entity.setBucketSize(agreement.bucketSize());
    entity.setExpectedStorageBytes(agreement.expectedStorageBytes());
    entity.setFragmentCount(agreement.fragmentCount());
    entity.setRedundancyScheme(agreement.redundancyScheme());
    entity.setPlannedReservationBytes(agreement.plannedReservationBytes());
    entity.setFileId(agreement.fileId());
    entity.setCreatedAt(agreement.createdAt());
    entity.setExpiresAt(agreement.expiresAt());
    entity.setRequesterSignature(agreement.requesterSignature());
    entity.setTargetSignature(agreement.targetSignature());

    TransferAuthorizationToken token = agreement.transferAuthorizationToken();
    if (token != null) {
      entity.setTransferToken(token.token());
      entity.setTransferTokenIssuedAt(token.issuedAt());
      entity.setTransferTokenExpiresAt(token.expiresAt());
    }

    entity.setRequesterTutorNodeId(agreement.requesterTutorNodeId());
    entity.setRequesterTutorBaseUrl(agreement.requesterTutorBaseUrl());

    return entity;
  }

  private NegotiationAgreement toDomain(final NegotiationAgreementJpaEntity entity) {
    TransferAuthorizationToken token = null;
    if (entity.getTransferToken() != null
        && entity.getTransferTokenIssuedAt() != null
        && entity.getTransferTokenExpiresAt() != null) {
      token =
          new TransferAuthorizationToken(
              entity.getTransferToken(),
              entity.getAgreementId(),
              entity.getTransferTokenIssuedAt(),
              entity.getTransferTokenExpiresAt());
    }

    // File_manifest_json eliminado; FileManifest no se hidrata post-load.
    // file_id preservado vía constructor 19-args con explicitFileId.
    return new NegotiationAgreement(
        entity.getAgreementId(),
        entity.getRequesterNodeId(),
        entity.getTargetNodeId(),
        NegotiationStatus.valueOf(entity.getStatus()),
        TransferMode.valueOf(entity.getTransferMode()),
        entity.getBucketSize(),
        entity.getExpectedStorageBytes(),
        entity.getFragmentCount(),
        entity.getRedundancyScheme(),
        entity.getPlannedReservationBytes(),
        null,
        entity.getFileId(),
        entity.getCreatedAt(),
        entity.getExpiresAt(),
        entity.getRequesterSignature(),
        entity.getTargetSignature(),
        token,
        entity.getRequesterTutorNodeId(),
        entity.getRequesterTutorBaseUrl());
  }
}
