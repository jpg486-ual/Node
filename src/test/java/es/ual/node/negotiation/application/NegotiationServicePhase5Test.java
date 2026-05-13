package es.ual.node.negotiation.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.identitysecurity.adapters.out.memory.InMemoryPublicKeyRegistry;
import es.ual.node.negotiation.adapters.out.memory.InMemoryAgreementRepository;
import es.ual.node.negotiation.adapters.out.memory.InMemoryCapacityPort;
import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.negotiation.domain.NegotiationAgreement;
import es.ual.node.negotiation.domain.NegotiationCreateRequest;
import es.ual.node.negotiation.domain.NegotiationStatus;
import es.ual.node.negotiation.domain.TransferMode;
import es.ual.node.negotiation.ports.out.AgreementRepository;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TDD tests for phase 5 negotiation and transfer authorization service. */
class NegotiationServicePhase5Test {

  private static final String REQUESTER = "node-requester";
  private static final String TARGET = "node-target";

  private InMemoryPublicKeyRegistry publicKeyRegistry;
  private InMemoryAgreementRepository agreementRepository;
  private InMemoryCapacityPort capacityPort;
  private AdjustableClock clock;
  private NegotiationProperties properties;
  private NegotiationService negotiationService;

  @BeforeEach
  void setUp() throws Exception {
    publicKeyRegistry = new InMemoryPublicKeyRegistry();
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
    keyPairGenerator.initialize(256);
    KeyPair requester = keyPairGenerator.generateKeyPair();
    KeyPair target = keyPairGenerator.generateKeyPair();
    publicKeyRegistry.register(REQUESTER, requester.getPublic());
    publicKeyRegistry.register(TARGET, target.getPublic());

    clock = new AdjustableClock(Instant.parse("2026-02-14T10:00:00Z"));
    agreementRepository = new InMemoryAgreementRepository(clock);
    capacityPort = new InMemoryCapacityPort(1_000_000_000L);

    properties = new NegotiationProperties();
    properties.setBucketMaxRatio(1.25d);
    properties.setQueueWindowSeconds(300);
    properties.setMaxConcurrentNegotiations(100);
    properties.setDefaultAgreementExpiration(120);

    negotiationService =
        new NegotiationService(
            publicKeyRegistry, agreementRepository, capacityPort, properties, clock);
  }

  @Test
  void agreementCreated() {
    NegotiationAgreement agreement = negotiationService.createAgreement(validRequest());

    assertNotNull(agreement.agreementId());
    assertEquals(NegotiationStatus.PENDING, agreement.status());
  }

  @Test
  void agreementConfirmed() {
    NegotiationAgreement pending = negotiationService.createAgreement(validRequest());

    NegotiationAgreement confirmed =
        negotiationService.confirmAgreement(pending.agreementId(), "target-signature");

    assertEquals(NegotiationStatus.CONFIRMED, confirmed.status());
    assertNotNull(confirmed.requesterSignature());
    assertNotNull(confirmed.targetSignature());
    assertNotNull(confirmed.transferAuthorizationToken());
  }

  @Test
  void agreementRejected() {
    NegotiationAgreement pending = negotiationService.createAgreement(validRequest());

    NegotiationAgreement rejected =
        negotiationService.rejectAgreement(pending.agreementId(), "capacity denied");

    assertEquals(NegotiationStatus.REJECTED, rejected.status());
  }

  @Test
  void cancelAllowsConfirmedToCancelledForDecommission() {
    // Relajación del state machine: un agreement CONFIRMED puede ser cancelado cuando el origen
    // lo decommissiona durante recovery (probe extension). Estados terminales
    // (REJECTED/CANCELLED/EXPIRED) siguen rechazando cancel.
    NegotiationAgreement pending = negotiationService.createAgreement(validRequest());
    NegotiationAgreement confirmed =
        negotiationService.confirmAgreement(pending.agreementId(), "target-signature");
    assertEquals(NegotiationStatus.CONFIRMED, confirmed.status());

    NegotiationAgreement cancelled = negotiationService.cancelAgreement(confirmed.agreementId());

    assertEquals(NegotiationStatus.CANCELLED, cancelled.status());
  }

  @Test
  void cancelRejectsTerminalStates() {
    NegotiationAgreement pending = negotiationService.createAgreement(validRequest());
    negotiationService.rejectAgreement(pending.agreementId(), "no capacity");

    assertThrows(
        NegotiationException.class,
        () -> negotiationService.cancelAgreement(pending.agreementId()));
  }

  @Test
  void expiredAgreementFailsOnConfirm() {
    NegotiationCreateRequest request =
        new NegotiationCreateRequest(
            REQUESTER,
            TARGET,
            1024L,
            4096L,
            TransferMode.FRAGMENTS_ONLY,
            4,
            "RS(6,4)",
            1,
            null,
            "requester-signature");
    NegotiationAgreement pending = negotiationService.createAgreement(request);

    clock.plusSeconds(5);

    assertThrows(
        NegotiationException.class,
        () -> negotiationService.confirmAgreement(pending.agreementId(), "target-signature"));
  }

  @Test
  void manifestValidationRejectsInvalidHash() {
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          FileManifest invalidManifest =
              new FileManifest(
                  "0d7f64c2-97cc-4400-a2a3-b3af056f85a1",
                  "/test",
                  "video.mov",
                  4096L,
                  3000L,
                  "zstd",
                  "invalid-hash",
                  4,
                  1024L,
                  6,
                  4,
                  List.of("fhash-1", "fhash-2"));

          NegotiationCreateRequest request =
              new NegotiationCreateRequest(
                  REQUESTER,
                  TARGET,
                  1024L,
                  4096L,
                  TransferMode.MANIFEST_ONLY,
                  4,
                  "RS(6,4)",
                  60,
                  invalidManifest,
                  "requester-signature");

          negotiationService.createAgreement(request);
        });
  }

  @Test
  void confirmAgreementReleasesCapacityWhenRepositorySaveFails() {
    AgreementRepository failingRepository =
        new AgreementRepository() {

          private final InMemoryAgreementRepository delegate =
              new InMemoryAgreementRepository(clock);

          @Override
          public NegotiationAgreement save(final NegotiationAgreement agreement) {
            if (agreement.status() == NegotiationStatus.CONFIRMED) {
              throw new IllegalStateException("persistence failure");
            }
            return delegate.save(agreement);
          }

          @Override
          public Optional<NegotiationAgreement> findById(final String agreementId) {
            return delegate.findById(agreementId);
          }

          @Override
          public int countPending() {
            return delegate.countPending();
          }
        };

    NegotiationService failingService =
        new NegotiationService(
            publicKeyRegistry, failingRepository, capacityPort, properties, clock);

    NegotiationAgreement pending = failingService.createAgreement(validRequest());

    assertThrows(
        IllegalStateException.class,
        () -> failingService.confirmAgreement(pending.agreementId(), "target-signature"));
    assertTrue(capacityPort.canReserve(1_000_000_000L));
  }

  private NegotiationCreateRequest validRequest() {
    return new NegotiationCreateRequest(
        REQUESTER,
        TARGET,
        1024L,
        4096L,
        TransferMode.FRAGMENTS_ONLY,
        4,
        "RS(6,4)",
        60,
        validManifest(),
        "requester-signature");
  }

  private FileManifest validManifest() {
    return new FileManifest(
        "0d7f64c2-97cc-4400-a2a3-b3af056f85a1",
        "/test",
        "video.mov",
        4096L,
        3000L,
        "zstd",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        4,
        1024L,
        6,
        4,
        List.of("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
  }

  /** Clock that can be advanced during tests. */
  private static final class AdjustableClock extends Clock {

    private Instant now;

    private AdjustableClock(final Instant now) {
      this.now = now;
    }

    /**
     * Advances internal time.
     *
     * @param seconds number of seconds to add
     */
    public void plusSeconds(final long seconds) {
      now = now.plusSeconds(seconds);
    }

    /** {@inheritDoc} */
    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    /** {@inheritDoc} */
    @Override
    public Clock withZone(final java.time.ZoneId zone) {
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Instant instant() {
      return now;
    }
  }
}
