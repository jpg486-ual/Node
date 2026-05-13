package es.ual.node.fragmentstorage.application;

/**
 * Lanzada por {@link FragmentCustodyService#store} cuando el nodo no tiene capacidad libre
 * suficiente para aceptar un nuevo fragment. El controller la mapea a {@code 507 Insufficient
 * Storage} con error code {@code CUSTODY_INSUFFICIENT_STORAGE}.
 */
public class CustodyInsufficientStorageException extends RuntimeException {

  public CustodyInsufficientStorageException(final String message) {
    super(message);
  }
}
