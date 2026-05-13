package es.ual.node.negotiation.ports.out;

import es.ual.node.negotiation.domain.NegotiationAgreement;
import java.util.Optional;

/** Port for agreement persistence operations. */
public interface AgreementRepository {

  /**
   * Saves an agreement.
   *
   * @param agreement agreement to persist
   * @return persisted agreement
   */
  NegotiationAgreement save(NegotiationAgreement agreement);

  /**
   * Finds agreement by id.
   *
   * @param agreementId agreement identifier
   * @return agreement when found
   */
  Optional<NegotiationAgreement> findById(String agreementId);

  /**
   * Counts pending agreements.
   *
   * @return pending agreement count
   */
  int countPending();
}
