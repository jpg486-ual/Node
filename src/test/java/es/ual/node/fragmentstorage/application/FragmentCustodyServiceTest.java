package es.ual.node.fragmentstorage.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import es.ual.node.fragmentstorage.adapters.out.memory.InMemoryCustodyFragmentPayloadPort;
import es.ual.node.fragmentstorage.adapters.out.memory.InMemoryCustodyFragmentPort;
import es.ual.node.fragmentstorage.adapters.out.memory.NoOpCustodyEventNotifier;
import es.ual.node.fragmentstorage.application.FragmentCustodyService.StoreFragmentRequest;
import es.ual.node.fragmentstorage.domain.CustodyFragment;
import es.ual.node.fragmentstorage.ports.out.CapacityCheckPort;
import es.ual.node.fragmentstorage.ports.out.CustodyEventNotifierPort;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FragmentCustodyService}. */
class FragmentCustodyServiceTest {

  private static final String SENDER_PUBLIC_KEY = "sender-pub-key";
  private static final String OTHER_PUBLIC_KEY = "intruder-pub-key";
  private static final long DEFAULT_CUSTODY_SECONDS = 3600L;
  private static final CapacityCheckPort UNLIMITED_CAPACITY = bytes -> true;

  private InMemoryCustodyFragmentPort custodyPort;
  private InMemoryCustodyFragmentPayloadPort payloadPort;
  private RecordingCustodyEventNotifier eventNotifier;
  private Clock clock;
  private FragmentCustodyService sut;

  @BeforeEach
  void setUp() {
    custodyPort = new InMemoryCustodyFragmentPort();
    payloadPort = new InMemoryCustodyFragmentPayloadPort();
    eventNotifier = new RecordingCustodyEventNotifier();
    clock = Clock.fixed(Instant.parse("2026-05-01T19:00:00Z"), ZoneId.of("UTC"));
    sut =
        new FragmentCustodyService(
            custodyPort,
            payloadPort,
            UNLIMITED_CAPACITY,
            eventNotifier,
            List.of(SENDER_PUBLIC_KEY),
            DEFAULT_CUSTODY_SECONDS,
            clock);
  }

  @Test
  void rejectsNullDependencies() {
    final CustodyEventNotifierPort noop = new NoOpCustodyEventNotifier();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FragmentCustodyService(
                null,
                payloadPort,
                UNLIMITED_CAPACITY,
                noop,
                List.of(SENDER_PUBLIC_KEY),
                DEFAULT_CUSTODY_SECONDS,
                clock));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FragmentCustodyService(
                custodyPort,
                null,
                UNLIMITED_CAPACITY,
                noop,
                List.of(SENDER_PUBLIC_KEY),
                DEFAULT_CUSTODY_SECONDS,
                clock));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FragmentCustodyService(
                custodyPort,
                payloadPort,
                null,
                noop,
                List.of(SENDER_PUBLIC_KEY),
                DEFAULT_CUSTODY_SECONDS,
                clock));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FragmentCustodyService(
                custodyPort,
                payloadPort,
                UNLIMITED_CAPACITY,
                null,
                List.of(SENDER_PUBLIC_KEY),
                DEFAULT_CUSTODY_SECONDS,
                clock));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FragmentCustodyService(
                custodyPort,
                payloadPort,
                UNLIMITED_CAPACITY,
                noop,
                List.of(SENDER_PUBLIC_KEY),
                0,
                clock));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FragmentCustodyService(
                custodyPort,
                payloadPort,
                UNLIMITED_CAPACITY,
                noop,
                List.of(SENDER_PUBLIC_KEY),
                DEFAULT_CUSTODY_SECONDS,
                null));
  }

  @Test
  void storesFragmentFromAuthorizedSender() {
    final byte[] payload = "hello fragment".getBytes();
    final CustodyFragment stored = sut.store(buildRequest(payload, SENDER_PUBLIC_KEY, null));

    assertEquals("frag-1", stored.fragmentId());
    assertEquals("agreement-1", stored.agreementId());
    assertEquals(payload.length, stored.sizeBytes());
    assertArrayEquals(payload, sut.findContent("frag-1"));
    assertEquals(stored.expiresAt(), clock.instant().plusSeconds(DEFAULT_CUSTODY_SECONDS));
  }

  @Test
  void rejectsSenderNotInWhitelist() {
    assertThrows(
        FragmentCustodyAuthorizationException.class,
        () -> sut.store(buildRequest("data".getBytes(), OTHER_PUBLIC_KEY, null)));
  }

  @Test
  void rejectsChecksumMismatch() {
    final byte[] payload = "tampered".getBytes();
    final StoreFragmentRequest req =
        new StoreFragmentRequest(
            "frag-1",
            "agreement-1",
            "node-a",
            SENDER_PUBLIC_KEY,
            "SHA-256",
            "0".repeat(64),
            payload,
            null);
    assertThrows(IllegalArgumentException.class, () -> sut.store(req));
  }

  @Test
  void rejectsWith507WhenCapacityExhausted() {
    final FragmentCustodyService sutNoCapacity =
        new FragmentCustodyService(
            custodyPort,
            payloadPort,
            bytes -> false, // simulate disk full
            eventNotifier,
            List.of(SENDER_PUBLIC_KEY),
            DEFAULT_CUSTODY_SECONDS,
            clock);
    final byte[] payload = "anything".getBytes();
    final StoreFragmentRequest req = buildRequest(payload, SENDER_PUBLIC_KEY, null);

    assertThrows(CustodyInsufficientStorageException.class, () -> sutNoCapacity.store(req));
    // Defensa-en-profundidad: la metadata NO debe persistirse cuando se rechaza por capacidad
    // — el rechazo ocurre antes del save.
    assertThrows(NoSuchElementException.class, () -> sutNoCapacity.findContent("frag-1"));
  }

  @Test
  void usesProvidedCustodySeconds() {
    final CustodyFragment stored = sut.store(buildRequest("p".getBytes(), SENDER_PUBLIC_KEY, 10L));
    assertEquals(stored.expiresAt(), clock.instant().plusSeconds(10L));
  }

  @Test
  void findContentThrowsWhenAbsent() {
    assertThrows(NoSuchElementException.class, () -> sut.findContent("ghost"));
  }

  @Test
  void findMetadataReadsBackStored() {
    sut.store(buildRequest("payload".getBytes(), SENDER_PUBLIC_KEY, null));
    final CustodyFragment metadata = sut.findMetadata("frag-1");
    assertEquals("frag-1", metadata.fragmentId());
    assertEquals(7, metadata.sizeBytes());
  }

  @Test
  void findMetadataThrowsWhenAbsent() {
    assertThrows(NoSuchElementException.class, () -> sut.findMetadata("ghost"));
  }

  @Test
  void listInventoryByRequesterReturnsItemsWithTtlRemaining() {
    sut.store(buildRequest(new byte[] {1, 2, 3}, SENDER_PUBLIC_KEY, null));

    final var inventory = sut.listInventoryByRequester("node-a");

    assertEquals(1, inventory.size());
    assertEquals("frag-1", inventory.get(0).fragmentId());
    assertEquals("agreement-1", inventory.get(0).agreementId());
    assertEquals(3, inventory.get(0).sizeBytes());
    // TTL remaining ≈ DEFAULT_CUSTODY_SECONDS (clock fijo, just stored)
    assertEquals(DEFAULT_CUSTODY_SECONDS, inventory.get(0).ttlRemainingSeconds());
  }

  @Test
  void listInventoryByRequesterReturnsEmptyForUnknownRequester() {
    sut.store(buildRequest(new byte[] {1, 2, 3}, SENDER_PUBLIC_KEY, null));

    assertEquals(0, sut.listInventoryByRequester("ghost-node").size());
  }

  @Test
  void listInventoryByRequesterRejectsBlank() {
    assertThrows(IllegalArgumentException.class, () -> sut.listInventoryByRequester(""));
    assertThrows(IllegalArgumentException.class, () -> sut.listInventoryByRequester(null));
  }

  @Test
  void storeNotifiesEventListenerWithSenderNodeId() {
    sut.store(buildRequest(new byte[] {1, 2, 3}, SENDER_PUBLIC_KEY, null));

    assertEquals(List.of("node-a"), eventNotifier.received);
  }

  @Test
  void storeRemainsDurableWhenNotifierThrows() {
    final FragmentCustodyService sutWithFailingNotifier =
        new FragmentCustodyService(
            custodyPort,
            payloadPort,
            UNLIMITED_CAPACITY,
            requesterNodeId -> {
              throw new RuntimeException("notifier exploded");
            },
            List.of(SENDER_PUBLIC_KEY),
            DEFAULT_CUSTODY_SECONDS,
            clock);

    // Invariant: notifier failures must not propagate, the custody store has already
    // succeeded by the time the after-store hook runs and must remain durable.
    final byte[] payload = "durable".getBytes();
    final CustodyFragment stored =
        sutWithFailingNotifier.store(buildRequest(payload, SENDER_PUBLIC_KEY, null));

    assertEquals("frag-1", stored.fragmentId());
    assertArrayEquals(payload, sutWithFailingNotifier.findContent("frag-1"));
  }

  @Test
  void storeRequestRejectsBlankFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StoreFragmentRequest(
                "",
                "agreement-1",
                "node-a",
                SENDER_PUBLIC_KEY,
                "SHA-256",
                "00",
                new byte[] {1},
                null));
  }

  private StoreFragmentRequest buildRequest(
      final byte[] payload, final String pubKey, final Long custodySeconds) {
    return new StoreFragmentRequest(
        "frag-1",
        "agreement-1",
        "node-a",
        pubKey,
        "SHA-256",
        sha256Hex(payload),
        payload,
        custodySeconds);
  }

  private static String sha256Hex(final byte[] payload) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(payload));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** Test double that records every {@code onCustodyFragmentStored} invocation. */
  private static final class RecordingCustodyEventNotifier implements CustodyEventNotifierPort {
    final List<String> received = new ArrayList<>();

    @Override
    public void onCustodyFragmentStored(final String requesterNodeId) {
      received.add(requesterNodeId);
    }
  }
}
