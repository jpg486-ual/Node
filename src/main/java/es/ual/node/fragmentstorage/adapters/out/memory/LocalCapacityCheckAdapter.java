package es.ual.node.fragmentstorage.adapters.out.memory;

import es.ual.node.fragmentstorage.ports.out.CapacityCheckPort;
import es.ual.node.fragmentstorage.ports.out.CustodyFragmentPort;

/**
 * Implementación de {@link CapacityCheckPort} que mide el espacio realmente ocupado por los custody
 * fragments persistidos por este nodo y lo compara contra el cap configurado. Sustituye al adapter
 * previo que delegaba en {@code negotiation.CapacityPort.canReserve} sobre el contador
 * `capacity_counter`, contador que solo se incrementaba en el path formal `/negotiate` y por tanto
 * se mantenía en 0 en producción (admission control silenciosamente OFF).
 *
 * <p>Coherente con el modelo asimétrico actual: el espacio que el nodo tiene "ocupado" en cada
 * momento es exactamente la suma de {@code custody_fragment.size_bytes}, no hay reservas formales
 * que tracker por separado.
 */
public class LocalCapacityCheckAdapter implements CapacityCheckPort {

  private final CustodyFragmentPort custodyFragmentPort;
  private final long maxBytes;

  public LocalCapacityCheckAdapter(
      final CustodyFragmentPort custodyFragmentPort, final long maxBytes) {
    if (custodyFragmentPort == null) {
      throw new IllegalArgumentException("custodyFragmentPort must not be null");
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be greater than zero");
    }
    this.custodyFragmentPort = custodyFragmentPort;
    this.maxBytes = maxBytes;
  }

  @Override
  public boolean canAccept(final long expectedBytes) {
    if (expectedBytes < 0) {
      return false;
    }
    final long currentlyOccupied = custodyFragmentPort.totalSizeBytes();
    return currentlyOccupied + expectedBytes <= maxBytes;
  }
}
