package es.ual.node.negotiation.ports.out;

/** Port used to evaluate and reserve target node capacity. */
public interface CapacityPort {

  /**
   * Checks if requested bytes can be reserved.
   *
   * @param expectedStorageBytes requested storage bytes
   * @return {@code true} when capacity is available
   */
  boolean canReserve(long expectedStorageBytes);

  /**
   * Reserves capacity for an operation key.
   *
   * @param reservationKey operation identifier used for idempotency
   * @param expectedStorageBytes reserved bytes
   */
  void reserve(String reservationKey, long expectedStorageBytes);

  /**
   * Commits a previously reserved operation.
   *
   * @param reservationKey operation identifier
   */
  void commit(String reservationKey);

  /**
   * Releases capacity associated with an operation.
   *
   * @param reservationKey operation identifier
   */
  void release(String reservationKey);
}
