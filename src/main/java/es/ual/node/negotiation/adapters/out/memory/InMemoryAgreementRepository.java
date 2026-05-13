package es.ual.node.negotiation.adapters.out.memory;

import es.ual.node.negotiation.domain.NegotiationAgreement;
import es.ual.node.negotiation.domain.NegotiationStatus;
import es.ual.node.negotiation.ports.out.AgreementRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory agreement repository with automatic expiration updates. */
public class InMemoryAgreementRepository implements AgreementRepository {

  private final Clock clock;
  private final Map<String, NegotiationAgreement> agreements = new ConcurrentHashMap<>();

  /**
   * Creates repository using supplied clock.
   *
   * @param clock clock for expiration checks
   */
  public InMemoryAgreementRepository(final Clock clock) {
    if (clock == null) {
      throw new IllegalArgumentException("clock must not be null");
    }
    this.clock = clock;
  }

  /** {@inheritDoc} */
  @Override
  public NegotiationAgreement save(final NegotiationAgreement agreement) {
    if (agreement == null) {
      throw new IllegalArgumentException("agreement must not be null");
    }
    cleanupExpired();
    agreements.put(agreement.agreementId(), agreement);
    return agreement;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<NegotiationAgreement> findById(final String agreementId) {
    if (agreementId == null || agreementId.isBlank()) {
      return Optional.empty();
    }
    cleanupExpired();
    return Optional.ofNullable(agreements.get(agreementId.trim()));
  }

  /** {@inheritDoc} */
  @Override
  public int countPending() {
    cleanupExpired();
    return (int)
        agreements.values().stream()
            .filter(agreement -> agreement.status() == NegotiationStatus.PENDING)
            .count();
  }

  private void cleanupExpired() {
    final Instant now = clock.instant();
    agreements.forEach(
        (id, agreement) -> {
          if (!agreement.isTerminal() && agreement.isExpiredAt(now)) {
            agreements.put(id, agreement.expire());
          }
        });
  }
}
