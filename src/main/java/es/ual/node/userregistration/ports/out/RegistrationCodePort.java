package es.ual.node.userregistration.ports.out;

import es.ual.node.userregistration.domain.RegistrationCode;
import java.time.Instant;
import java.util.Optional;

/** Invitation code persistence boundary. */
public interface RegistrationCodePort {

  /**
   * Persists invitation code.
   *
   * @param registrationCode code model
   */
  void save(RegistrationCode registrationCode);

  /**
   * Resolves code by value.
   *
   * @param code code value
   * @return optional code
   */
  Optional<RegistrationCode> findByCode(String code);

  /**
   * Marks code as consumed.
   *
   * @param code code value
   * @param usedAt usage instant
   */
  void markUsed(String code, Instant usedAt);
}
