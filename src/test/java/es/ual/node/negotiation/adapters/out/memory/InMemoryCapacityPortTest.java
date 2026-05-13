package es.ual.node.negotiation.adapters.out.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link InMemoryCapacityPort}. */
class InMemoryCapacityPortTest {

  private InMemoryCapacityPort capacityPort;

  @BeforeEach
  void setUp() {
    capacityPort = new InMemoryCapacityPort(100L);
  }

  @Test
  void reserveAndCommitConsumeCapacity() {
    capacityPort.reserve("op-1", 60L);
    capacityPort.commit("op-1");

    assertTrue(capacityPort.canReserve(40L));
    assertFalse(capacityPort.canReserve(41L));
  }

  @Test
  void reserveIsIdempotentForSameKeyAndBytes() {
    capacityPort.reserve("op-1", 60L);
    capacityPort.reserve("op-1", 60L);

    assertFalse(capacityPort.canReserve(41L));
  }

  @Test
  void reserveFailsWhenSameKeyChangesBytes() {
    capacityPort.reserve("op-1", 60L);

    assertThrows(IllegalArgumentException.class, () -> capacityPort.reserve("op-1", 61L));
  }

  @Test
  void releaseFreesCapacityAndIsIdempotent() {
    capacityPort.reserve("op-1", 60L);
    capacityPort.commit("op-1");

    capacityPort.release("op-1");
    capacityPort.release("op-1");

    assertTrue(capacityPort.canReserve(100L));
  }

  @Test
  void reserveFailsWhenCapacityIsInsufficient() {
    capacityPort.reserve("op-1", 80L);

    assertThrows(IllegalArgumentException.class, () -> capacityPort.reserve("op-2", 25L));
  }

  @Test
  void commitFailsWhenReservationDoesNotExist() {
    assertThrows(IllegalArgumentException.class, () -> capacityPort.commit("op-missing"));
  }

  @Test
  void releasedReservationKeyCannotBeReused() {
    capacityPort.reserve("op-1", 40L);
    capacityPort.release("op-1");

    assertThrows(IllegalArgumentException.class, () -> capacityPort.reserve("op-1", 40L));
  }

  @Test
  void releaseUnknownReservationIsNoOp() {
    assertDoesNotThrow(() -> capacityPort.release("missing-key"));
  }
}
