package es.ual.node.fragmentstorage.ports.out;

/**
 * Outbound port que pregunta si el nodo custodio dispone de capacidad libre suficiente para aceptar
 * un nuevo fragment. Se invoca al inicio del flujo {@link
 * es.ual.node.fragmentstorage.application.FragmentCustodyService#store}.
 *
 * <p>Esta verificación se hace antes de cualquier persistencia para evitar trabajo (checksum, IO)
 * que sería revertido.
 */
public interface CapacityCheckPort {

  /**
   * Indica si el nodo dispone de al menos {@code expectedBytes} libres para aceptar un nuevo
   * fragment.
   *
   * @param expectedBytes tamaño en bytes del fragment entrante
   * @return {@code true} si hay capacidad disponible
   */
  boolean canAccept(long expectedBytes);
}
