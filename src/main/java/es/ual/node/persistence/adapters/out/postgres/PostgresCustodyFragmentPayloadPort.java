package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPayloadPort;
import es.ual.node.persistence.jpa.CustodyFragmentPayloadJpaEntity;
import es.ual.node.persistence.jpa.CustodyFragmentPayloadJpaRepository;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed custody-domain fragment payload bytes adapter. */
public class PostgresCustodyFragmentPayloadPort implements CustodyFragmentPayloadPort {

  private final CustodyFragmentPayloadJpaRepository repository;

  public PostgresCustodyFragmentPayloadPort(final CustodyFragmentPayloadJpaRepository repository) {
    if (repository == null) {
      throw new IllegalArgumentException("repository must not be null");
    }
    this.repository = repository;
  }

  @Override
  @Transactional
  public void save(final String fragmentId, final byte[] payload) {
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    if (payload == null || payload.length == 0) {
      throw new IllegalArgumentException("payload must not be null or empty");
    }
    final CustodyFragmentPayloadJpaEntity entity = new CustodyFragmentPayloadJpaEntity();
    entity.setFragmentId(fragmentId.trim());
    entity.setPayload(payload.clone());
    repository.save(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<byte[]> findByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return Optional.empty();
    }
    return repository
        .findById(fragmentId.trim())
        .map(CustodyFragmentPayloadJpaEntity::getPayload)
        .map(byte[]::clone);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean exists(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return false;
    }
    return repository.existsById(fragmentId.trim());
  }

  @Override
  @Transactional
  public void deleteByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return;
    }
    repository.deleteById(fragmentId.trim());
  }
}
