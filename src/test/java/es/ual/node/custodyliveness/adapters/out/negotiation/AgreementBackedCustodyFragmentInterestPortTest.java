package es.ual.node.custodyliveness.adapters.out.negotiation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFragmentPlacementPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFsEntryPort;
import es.ual.node.filesystem.domain.FragmentHealthStatus;
import es.ual.node.filesystem.domain.FragmentPlacement;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import es.ual.node.negotiation.adapters.out.memory.InMemoryAgreementRepository;
import es.ual.node.negotiation.domain.FileManifest;
import es.ual.node.negotiation.domain.NegotiationAgreement;
import es.ual.node.negotiation.domain.NegotiationStatus;
import es.ual.node.negotiation.domain.TransferMode;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AgreementBackedCustodyFragmentInterestPort}. */
class AgreementBackedCustodyFragmentInterestPortTest {

  private static final String LOCAL_NODE_ID = "local-node-id";
  private static final String REMOTE_NODE_ID = "remote-node-id";
  private static final String REMOTE_BASE_URL = "http://node2:8080";
  private static final String AGREEMENT_ID = "agreement-1";
  private static final String FRAGMENT_ID = "fragment-1";
  private static final String FILE_ID = "11111111-1111-1111-1111-111111111111";
  private static final String CHECKSUM_HEX = "a".repeat(64);
  private static final String USERNAME = "alice";
  private static final String PATH = "/docs/report.pdf";
  private static final Instant FIXED_NOW = Instant.parse("2026-04-29T10:00:00Z");

  private InMemoryFsEntryPort fsEntryPort;
  private InMemoryFragmentPlacementPort fragmentPlacementPort;
  private InMemoryAgreementRepository agreementRepository;
  private CustodyLivenessProperties livenessProperties;
  private NodeIdentityContext localIdentity;
  private Clock clock;
  private AgreementBackedCustodyFragmentInterestPort sut;

  @BeforeEach
  void setUp() throws Exception {
    clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
    fsEntryPort = new InMemoryFsEntryPort();
    fragmentPlacementPort = new InMemoryFragmentPlacementPort();
    agreementRepository = new InMemoryAgreementRepository(clock);
    livenessProperties = new CustodyLivenessProperties();
    livenessProperties.setRemoteBaseUrls(Map.of(REMOTE_NODE_ID, REMOTE_BASE_URL));
    localIdentity = nodeIdentity(LOCAL_NODE_ID);
    sut =
        new AgreementBackedCustodyFragmentInterestPort(
            agreementRepository,
            localIdentity,
            fsEntryPort,
            fragmentPlacementPort,
            livenessProperties,
            clock);
  }

  @Test
  void rejectsNullDependencies() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgreementBackedCustodyFragmentInterestPort(
                null,
                localIdentity,
                fsEntryPort,
                fragmentPlacementPort,
                livenessProperties,
                clock));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgreementBackedCustodyFragmentInterestPort(
                agreementRepository,
                null,
                fsEntryPort,
                fragmentPlacementPort,
                livenessProperties,
                clock));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgreementBackedCustodyFragmentInterestPort(
                agreementRepository,
                localIdentity,
                null,
                fragmentPlacementPort,
                livenessProperties,
                clock));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgreementBackedCustodyFragmentInterestPort(
                agreementRepository, localIdentity, fsEntryPort, null, livenessProperties, clock));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgreementBackedCustodyFragmentInterestPort(
                agreementRepository,
                localIdentity,
                fsEntryPort,
                fragmentPlacementPort,
                null,
                clock));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgreementBackedCustodyFragmentInterestPort(
                agreementRepository,
                localIdentity,
                fsEntryPort,
                fragmentPlacementPort,
                livenessProperties,
                null));
  }

  @Test
  void returnsFalseWhenFragmentNull() {
    assertFalse(sut.isStillRequired(null, REMOTE_NODE_ID));
  }

  @Test
  void returnsTrueWhenAgreementIdBlank() {
    final CustodyProbeFragment fragment =
        new CustodyProbeFragment(FRAGMENT_ID, "", CHECKSUM_HEX, 1024L);
    assertTrue(sut.isStillRequired(fragment, REMOTE_NODE_ID));
  }

  @Test
  void returnsFalseWhenAgreementNotFoundAndNoPlacement() {
    // No agreement, no placement: legitimately not required (e.g. file deleted).
    final CustodyProbeFragment fragment = fragment();
    assertFalse(sut.isStillRequired(fragment, REMOTE_NODE_ID));
  }

  @Test
  void returnsFalseWhenAgreementTerminal() {
    agreementRepository.save(validAgreement(NegotiationStatus.CONFIRMED).cancel());
    persistFsEntry(false);
    assertFalse(sut.isStillRequired(fragment(), REMOTE_NODE_ID));
  }

  @Test
  void returnsFalseWhenStatusNotConfirmed() {
    agreementRepository.save(validAgreement(NegotiationStatus.PENDING));
    persistFsEntry(false);
    assertFalse(sut.isStillRequired(fragment(), REMOTE_NODE_ID));
  }

  @Test
  void returnsFalseWhenLocalNotRequester() {
    final NegotiationAgreement otherRequester =
        new NegotiationAgreement(
            AGREEMENT_ID,
            "different-requester",
            REMOTE_NODE_ID,
            NegotiationStatus.CONFIRMED,
            TransferMode.FRAGMENTS_ONLY,
            1024L,
            4096L,
            4,
            "RS(6,4)",
            60L,
            validManifest(),
            FIXED_NOW.minusSeconds(10),
            FIXED_NOW.plusSeconds(3600),
            "requester-signature",
            "target-signature",
            null,
            null,
            null);
    agreementRepository.save(otherRequester);
    persistFsEntry(false);
    assertFalse(sut.isStillRequired(fragment(), REMOTE_NODE_ID));
  }

  @Test
  void returnsFalseWhenRequesterNodeIdBlank() {
    agreementRepository.save(validAgreement(NegotiationStatus.CONFIRMED));
    persistFsEntry(false);
    assertFalse(sut.isStillRequired(fragment(), ""));
  }

  @Test
  void returnsFalseWhenRequesterNotTarget() {
    agreementRepository.save(validAgreement(NegotiationStatus.CONFIRMED));
    persistFsEntry(false);
    assertFalse(sut.isStillRequired(fragment(), "other-target"));
  }

  @Test
  void returnsTrueWhenAllChecksPassAndFsEntryActive() {
    agreementRepository.save(validAgreement(NegotiationStatus.CONFIRMED));
    persistFsEntry(false);
    assertTrue(sut.isStillRequired(fragment(), REMOTE_NODE_ID));
  }

  @Test
  void returnsFalseWhenFsEntryDeleted() {
    agreementRepository.save(validAgreement(NegotiationStatus.CONFIRMED));
    persistFsEntry(true);
    assertFalse(sut.isStillRequired(fragment(), REMOTE_NODE_ID));
  }

  @Test
  void returnsFalseWhenFsEntryNotFound() {
    agreementRepository.save(validAgreement(NegotiationStatus.CONFIRMED));
    // No FsEntry persisted: simulates an orphan fileId after an overwrite that
    // bumped fileId to a new UUID and rendered the previous manifest dangling.
    assertFalse(sut.isStillRequired(fragment(), REMOTE_NODE_ID));
  }

  @Test
  void returnsTrueWhenAgreementHasNoManifest() {
    final NegotiationAgreement noManifest =
        new NegotiationAgreement(
            AGREEMENT_ID,
            LOCAL_NODE_ID,
            REMOTE_NODE_ID,
            NegotiationStatus.CONFIRMED,
            TransferMode.FRAGMENTS_ONLY,
            1024L,
            4096L,
            null,
            null,
            60L,
            null,
            FIXED_NOW.minusSeconds(10),
            FIXED_NOW.plusSeconds(3600),
            "requester-signature",
            "target-signature",
            null,
            null,
            null);
    agreementRepository.save(noManifest);
    // No FsEntry persisted: should still be required because there is no
    // manifest to cross-check against the FS layer.
    assertTrue(sut.isStillRequired(fragment(), REMOTE_NODE_ID));
  }

  // ---------- synthetic agreement (direct upload) ----------

  @Test
  void returnsTrueWhenAgreementMissingButPlacementExistsAndFsEntryActive() {
    // El upload directo del cliente genera agreementId = "client-upload-<UUID>" sintético
    // que NO se persiste en negotiation_agreement.
    // El placement SÍ existe en client_fragment_placement. La probe pregunta por el
    // fragmentId; antes del fix devolvía false → custodian purgaba → archivo perdido.
    fragmentPlacementPort.save(syntheticPlacement(REMOTE_BASE_URL));
    persistFsEntry(false);
    assertTrue(sut.isStillRequired(fragment(), REMOTE_NODE_ID));
  }

  @Test
  void returnsFalseWhenAgreementMissingAndPlacementMissing() {
    // Sin agreement Y sin placement: el origen genuinamente no conoce este fragment.
    assertFalse(sut.isStillRequired(fragment(), REMOTE_NODE_ID));
  }

  @Test
  void returnsFalseWhenPlacementExistsButFsEntryDeleted() {
    fragmentPlacementPort.save(syntheticPlacement(REMOTE_BASE_URL));
    persistFsEntry(true);
    assertFalse(sut.isStillRequired(fragment(), REMOTE_NODE_ID));
  }

  @Test
  void returnsFalseWhenPlacementExistsButCustodianMismatch() {
    // El placement asigna el fragment a node2 pero la probe llega firmada por node3 (que
    // no es el custodian legítimo). Rechaza para evitar que un peer no-custodian "reclame"
    // un fragment que no le pertenece.
    fragmentPlacementPort.save(syntheticPlacement(REMOTE_BASE_URL));
    persistFsEntry(false);
    assertFalse(sut.isStillRequired(fragment(), "unknown-node-id"));
  }

  @Test
  void returnsTrueWhenSelfCustodyMatchesLocalBaseUrl() {
    // Self-custody legítimo: el origen también custodia 1 fragment de su propio
    // archivo. El placement.custodianBaseUrl = local baseUrl; la probe llega con el
    // requesterNodeId = nodeIdentityContext.nodeId().
    final String localBaseUrl = "http://node1:8080";
    livenessProperties.setRemoteBaseUrls(
        Map.of(REMOTE_NODE_ID, REMOTE_BASE_URL, LOCAL_NODE_ID, localBaseUrl));
    fragmentPlacementPort.save(syntheticPlacement(localBaseUrl));
    persistFsEntry(false);
    assertTrue(sut.isStillRequired(fragment(), LOCAL_NODE_ID));
  }

  // ---------- helpers ----------

  private FragmentPlacement syntheticPlacement(final String custodianBaseUrl) {
    return new FragmentPlacement(
        FILE_ID,
        FRAGMENT_ID,
        0,
        0,
        false,
        "peer@" + custodianBaseUrl,
        custodianBaseUrl,
        "client-upload-" + java.util.UUID.randomUUID(),
        CHECKSUM_HEX,
        1024L,
        FIXED_NOW.minusSeconds(60),
        FragmentHealthStatus.OK,
        null,
        0);
  }

  private NegotiationAgreement validAgreement(final NegotiationStatus status) {
    return new NegotiationAgreement(
        AGREEMENT_ID,
        LOCAL_NODE_ID,
        REMOTE_NODE_ID,
        status,
        TransferMode.FRAGMENTS_ONLY,
        1024L,
        4096L,
        4,
        "RS(6,4)",
        60L,
        validManifest(),
        FIXED_NOW.minusSeconds(10),
        FIXED_NOW.plusSeconds(3600),
        "requester-signature",
        "target-signature",
        null,
        null,
        null);
  }

  private FileManifest validManifest() {
    return new FileManifest(
        FILE_ID,
        "/docs",
        "report.pdf",
        4096L,
        3000L,
        "zstd",
        CHECKSUM_HEX,
        4,
        1024L,
        6,
        4,
        List.of(CHECKSUM_HEX, CHECKSUM_HEX, CHECKSUM_HEX, CHECKSUM_HEX));
  }

  private void persistFsEntry(final boolean deleted) {
    final FsEntry entry =
        new FsEntry(
            "entry-1",
            USERNAME,
            PATH,
            FsEntryType.FILE,
            4096L,
            deleted ? null : CHECKSUM_HEX,
            deleted ? null : FILE_ID,
            1L,
            FIXED_NOW.minusSeconds(60),
            deleted);
    if (deleted) {
      // Soft-deleted entries cannot be located by fileId (it is null on the row),
      // which is exactly the scenario the cross-check must catch. Persist the
      // active entry first, then mark it deleted, mimicking the runtime soft
      // delete that preserves entryId/path while clearing fileId.
      final FsEntry active =
          new FsEntry(
              "entry-1",
              USERNAME,
              PATH,
              FsEntryType.FILE,
              4096L,
              CHECKSUM_HEX,
              FILE_ID,
              1L,
              FIXED_NOW.minusSeconds(120),
              false);
      fsEntryPort.save(active);
      fsEntryPort.save(entry);
    } else {
      fsEntryPort.save(entry);
    }
  }

  private CustodyProbeFragment fragment() {
    return new CustodyProbeFragment(FRAGMENT_ID, AGREEMENT_ID, CHECKSUM_HEX, 1024L);
  }

  private static NodeIdentityContext nodeIdentity(final String nodeId) throws Exception {
    final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(256);
    final KeyPair keyPair = generator.generateKeyPair();
    return new NodeIdentityContext(nodeId, keyPair.getPublic(), keyPair.getPrivate());
  }
}
