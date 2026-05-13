package es.ual.node.userregistration.adapters.out.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.userregistration.domain.UserAccount;
import es.ual.node.userregistration.domain.UserRole;
import es.ual.node.userregistration.ports.out.UserQuotaPort;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link InMemoryUserQuotaPort}. */
class InMemoryUserQuotaPortTest {

  private static final long BYTES_PER_MB = 1024L * 1024L;
  private static final String ALICE = "alice";
  private static final int ALICE_QUOTA_MB = 10;

  private InMemoryUserAccountPort userAccountPort;
  private UserQuotaPort sut;

  @BeforeEach
  void setUp() {
    userAccountPort = new InMemoryUserAccountPort();
    userAccountPort.save(
        new UserAccount(ALICE, "hash", ALICE_QUOTA_MB, UserRole.END_USER, Instant.EPOCH));
    sut = new InMemoryUserQuotaPort(userAccountPort);
  }

  @Test
  void rejectsNullDependencies() {
    assertThrows(IllegalArgumentException.class, () -> new InMemoryUserQuotaPort(null));
  }

  @Test
  void reservesUpToQuotaLimit() {
    final long fullQuota = ALICE_QUOTA_MB * BYTES_PER_MB;
    assertTrue(sut.tryReserve(ALICE, fullQuota));
    assertEquals(fullQuota, sut.usedBytes(ALICE));
  }

  @Test
  void refusesReservationThatWouldExceedQuota() {
    final long fullQuota = ALICE_QUOTA_MB * BYTES_PER_MB;
    assertTrue(sut.tryReserve(ALICE, fullQuota));
    assertFalse(sut.tryReserve(ALICE, 1L));
    assertEquals(fullQuota, sut.usedBytes(ALICE));
  }

  @Test
  void multipleReservesAccumulate() {
    assertTrue(sut.tryReserve(ALICE, 1024L));
    assertTrue(sut.tryReserve(ALICE, 2048L));
    assertEquals(3072L, sut.usedBytes(ALICE));
  }

  @Test
  void releaseFreesBytesForFurtherReservation() {
    final long halfQuota = (ALICE_QUOTA_MB * BYTES_PER_MB) / 2;
    assertTrue(sut.tryReserve(ALICE, halfQuota));
    sut.release(ALICE, halfQuota);
    assertEquals(0L, sut.usedBytes(ALICE));
    assertTrue(sut.tryReserve(ALICE, ALICE_QUOTA_MB * BYTES_PER_MB));
  }

  @Test
  void releaseClampsToZero() {
    sut.release(ALICE, 1_000_000L);
    assertEquals(0L, sut.usedBytes(ALICE));
  }

  @Test
  void usedBytesIsZeroForUnknownUser() {
    assertEquals(0L, sut.usedBytes("bob"));
  }

  @Test
  void rejectsBlankUsername() {
    assertThrows(IllegalArgumentException.class, () -> sut.tryReserve(" ", 1024L));
    assertThrows(IllegalArgumentException.class, () -> sut.release("", 1024L));
    assertThrows(IllegalArgumentException.class, () -> sut.usedBytes(null));
  }

  @Test
  void rejectsNegativeBytes() {
    assertThrows(IllegalArgumentException.class, () -> sut.tryReserve(ALICE, -1L));
    assertThrows(IllegalArgumentException.class, () -> sut.release(ALICE, -1L));
  }

  @Test
  void rejectsReservationForUnknownUser() {
    assertThrows(IllegalArgumentException.class, () -> sut.tryReserve("ghost", 1024L));
  }
}
