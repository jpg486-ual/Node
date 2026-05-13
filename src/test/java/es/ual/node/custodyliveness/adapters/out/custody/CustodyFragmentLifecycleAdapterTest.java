package es.ual.node.custodyliveness.adapters.out.custody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ual.node.fragmentstorage.adapters.out.memory.InMemoryCustodyFragmentPayloadPort;
import es.ual.node.fragmentstorage.adapters.out.memory.InMemoryCustodyFragmentPort;
import es.ual.node.fragmentstorage.domain.CustodyFragment;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CustodyFragmentLifecycleAdapter}. */
class CustodyFragmentLifecycleAdapterTest {

  private InMemoryCustodyFragmentPort custodyPort;
  private InMemoryCustodyFragmentPayloadPort payloadPort;
  private CustodyFragmentLifecycleAdapter sut;

  @BeforeEach
  void setUp() {
    custodyPort = new InMemoryCustodyFragmentPort();
    payloadPort = new InMemoryCustodyFragmentPayloadPort();
    sut = new CustodyFragmentLifecycleAdapter(custodyPort, payloadPort);
  }

  @Test
  void extendCustodyAddsSecondsToExistingFragment() {
    final Instant storedAt = Instant.parse("2026-04-18T12:00:00Z");
    final Instant expiresAt = Instant.parse("2026-04-18T12:05:00Z");
    custodyPort.save(stored("frag-1", storedAt, expiresAt));

    sut.extendCustody("frag-1", 300L);

    final CustodyFragment after = custodyPort.findByFragmentId("frag-1").orElseThrow();
    assertThat(after.expiresAt()).isEqualTo(expiresAt.plusSeconds(300L));
  }

  @Test
  void extendCustodyIsNoopForUnknownFragment() {
    // Idempotent: a fragment swept by consistency or released elsewhere must not blow up.
    sut.extendCustody("unknown", 60L);
    assertThat(custodyPort.findAll()).isEmpty();
  }

  @Test
  void extendCustodyRejectsNonPositiveSeconds() {
    custodyPort.save(
        stored(
            "frag-1",
            Instant.parse("2026-04-18T12:00:00Z"),
            Instant.parse("2026-04-18T12:05:00Z")));
    assertThatThrownBy(() -> sut.extendCustody("frag-1", 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sut.extendCustody("frag-1", -1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void findByFragmentIdReturnsCurrentMetadata() {
    final Instant expiresAt = Instant.parse("2026-04-18T12:05:00Z");
    custodyPort.save(stored("frag-1", Instant.parse("2026-04-18T12:00:00Z"), expiresAt));

    assertThat(sut.findByFragmentId("frag-1")).isPresent();
    assertThat(sut.findByFragmentId("frag-1").get().expiresAt()).isEqualTo(expiresAt);
    assertThat(sut.findByFragmentId(null)).isEmpty();
    assertThat(sut.findByFragmentId("")).isEmpty();
  }

  @Test
  void releaseCustodyRemovesBothMetadataAndPayload() {
    custodyPort.save(
        stored(
            "frag-1",
            Instant.parse("2026-04-18T12:00:00Z"),
            Instant.parse("2026-04-18T12:05:00Z")));
    payloadPort.save("frag-1", "demo".getBytes(StandardCharsets.UTF_8));

    sut.releaseCustody("frag-1");

    assertThat(custodyPort.findByFragmentId("frag-1")).isEmpty();
    assertThat(payloadPort.findByFragmentId("frag-1")).isEmpty();
  }

  @Test
  void releaseCustodyIgnoresNullAndBlank() {
    sut.releaseCustody(null);
    sut.releaseCustody("");
    sut.releaseCustody("   ");
  }

  private CustodyFragment stored(
      final String fragmentId, final Instant storedAt, final Instant expiresAt) {
    return new CustodyFragment(
        fragmentId,
        "agreement-" + fragmentId,
        "node-a",
        "SHA-256",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        16,
        storedAt,
        expiresAt);
  }
}
