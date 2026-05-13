package es.ual.node.filesystem.application;

/**
 * Lanzada por el cliente HTTP de distribución de fragments cuando el custodian remoto responde
 * {@code 507 Insufficient Storage}. El distribuidor del origen la traduce a un WARN estructurado
 * (admin-notice del lado origen) y aborta el upload con error code {@code
 * CUSTODIAN_INSUFFICIENT_STORAGE} (HTTP 503).
 *
 * <p>Distinto de {@link InsufficientCustodiansException}: aquellas se disparan cuando discovery no
 * resuelve N candidatos. Esta se dispara cuando un candidato concreto rechaza por capacidad.
 */
public class CustodianInsufficientStorageException extends RuntimeException {

  private final String custodianBaseUrl;

  public CustodianInsufficientStorageException(
      final String custodianBaseUrl, final String message) {
    super(message);
    this.custodianBaseUrl = custodianBaseUrl;
  }

  public String custodianBaseUrl() {
    return custodianBaseUrl;
  }
}
