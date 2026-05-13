package es.ual.node.bootstrap.configuration;

import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import es.ual.node.fragmentstorage.application.FragmentStorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fails the Spring context at startup when the custody liveness defaults form an arithmetic
 * absurdity.
 *
 * <p>Invariante congelada: {@code baseIntervalSeconds < renewalHorizonSeconds <
 * fragmentstorage.default-custody-seconds}. Razonamiento:
 *
 * <ul>
 *   <li>{@code baseIntervalSeconds < renewalHorizonSeconds}: cada probe outbound debe ocurrir antes
 *       de que el horizonte de renewal de cualquier fragmento se cumpla, garantizando que la
 *       extensión llegue a tiempo.
 *   <li>{@code renewalHorizonSeconds < fragmentstorage.default-custody-seconds}: el horizonte de
 *       renewal sólo es útil dentro de la vida del fragment; ponerlo igual o mayor que el TTL
 *       convierte el sistema en "renueva todos los fragmentos en cada probe", lo que es equivalente
 *       a no tener TTL.
 * </ul>
 *
 * <p>Si el invariante falla, mejor que el contexto Spring NO arranque a que arranque con valores
 * silenciosamente erróneos que reaparezcan como fragmentos perdidos en producción 5 minutos
 * después.
 */
@Component
@ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
public class CustodyLivenessStartupValidator {

  /**
   * Creates validator. Lanza {@link IllegalStateException} si el invariante no se cumple.
   *
   * @param custodyLivenessProperties cadencia + horizonte de probes
   * @param fragmentStorageProperties TTL del fragment custody peer-side
   */
  public CustodyLivenessStartupValidator(
      final CustodyLivenessProperties custodyLivenessProperties,
      final FragmentStorageProperties fragmentStorageProperties) {
    if (custodyLivenessProperties == null || fragmentStorageProperties == null) {
      throw new IllegalArgumentException(
          "CustodyLivenessStartupValidator dependencies must not be null");
    }
    final long baseIntervalSeconds = custodyLivenessProperties.getBaseIntervalSeconds();
    final long renewalHorizonSeconds = custodyLivenessProperties.getRenewalHorizonSeconds();
    final long defaultCustodySeconds = fragmentStorageProperties.getDefaultCustodySeconds();

    if (baseIntervalSeconds <= 0 || renewalHorizonSeconds <= 0 || defaultCustodySeconds <= 0) {
      throw new IllegalStateException(
          "custody liveness intervals must be positive: baseIntervalSeconds="
              + baseIntervalSeconds
              + ", renewalHorizonSeconds="
              + renewalHorizonSeconds
              + ", defaultCustodySeconds="
              + defaultCustodySeconds);
    }

    if (baseIntervalSeconds >= renewalHorizonSeconds) {
      throw new IllegalStateException(
          "invariant violated: node.custody-liveness.baseIntervalSeconds ("
              + baseIntervalSeconds
              + ") must be strictly less than node.custody-liveness.renewalHorizonSeconds ("
              + renewalHorizonSeconds
              + ")");
    }

    if (renewalHorizonSeconds >= defaultCustodySeconds) {
      throw new IllegalStateException(
          "invariant violated: node.custody-liveness.renewalHorizonSeconds ("
              + renewalHorizonSeconds
              + ") must be strictly less than node.fragmentstorage.default-custody-seconds ("
              + defaultCustodySeconds
              + ")");
    }
  }
}
