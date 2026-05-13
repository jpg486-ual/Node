package es.ual.node.filesystem.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.discovery.application.DiscoveryProperties;
import es.ual.node.discovery.application.DiscoveryQueryCache;
import es.ual.node.discovery.application.DiscoveryService;
import es.ual.node.discovery.application.DiscoveryUnreachableException;
import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import es.ual.node.discovery.domain.DiscoveryRequest;
import es.ual.node.discovery.domain.DiscoveryResponse;
import es.ual.node.discovery.ports.out.DiscoveryCandidateDirectoryPort;
import es.ual.node.discovery.ports.out.RemoteDiscoveryQueryClientPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFileManifestPort;
import es.ual.node.filesystem.adapters.out.memory.InMemoryFragmentPlacementPort;
import es.ual.node.filesystem.domain.FsEntry;
import es.ual.node.filesystem.domain.FsEntryType;
import es.ual.node.filesystem.ports.out.RemoteFragmentDistributionClientPort;
import es.ual.node.identitysecurity.adapters.out.memory.InMemoryPublicKeyRegistry;
import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsCodecAdapter;
import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsIntegrityVerifier;
import es.ual.node.reedsolomon.domain.RsScheme;
import es.ual.node.userregistration.adapters.out.memory.InMemoryUserAccountPort;
import es.ual.node.userregistration.adapters.out.memory.InMemoryUserQuotaPort;
import es.ual.node.userregistration.domain.UserAccount;
import es.ual.node.userregistration.domain.UserRole;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for la integración con dynamic Discovery en {@link FileContentDistributionService}. El
 * path legacy no-discovery se cubre en {@code FileContentDistributionServiceTest}; esta clase cubre
 * comportamientos que solo se activan cuando {@link FileContentDistributionService.DiscoveryWiring}
 * está cableado.
 */
class FileContentDistributionServiceDiscoveryTest {

  private static final String USERNAME = "alice";
  private static final long MAX_BYTES = 10L * 1024L * 1024L;
  private static final RsScheme SCHEME = new RsScheme(3, 2, 16);
  private static final String LOCAL_NODE_ID = "local-origin";

  private InMemoryUserAccountPort accountPort;
  private InMemoryUserQuotaPort quotaPort;
  private InMemoryFileManifestPort manifestPort;
  private InMemoryFragmentPlacementPort placementPort;
  private RecordingRemoteClient remoteClient;
  private InMemoryRsCodecAdapter rsCodec;
  private InMemoryCandidateDirectory directory;
  private DiscoveryService discoveryService;
  private DiscoveryQueryCache cache;
  private Clock clock;

  @BeforeEach
  void setUp() throws Exception {
    accountPort = new InMemoryUserAccountPort();
    accountPort.save(new UserAccount(USERNAME, "hash", 10, UserRole.END_USER, Instant.EPOCH));
    quotaPort = new InMemoryUserQuotaPort(accountPort);
    manifestPort = new InMemoryFileManifestPort();
    placementPort = new InMemoryFragmentPlacementPort();
    remoteClient = new RecordingRemoteClient();
    rsCodec = new InMemoryRsCodecAdapter();
    clock = Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneId.of("UTC"));

    final InMemoryPublicKeyRegistry keyRegistry = new InMemoryPublicKeyRegistry();
    final var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(256);
    keyRegistry.register(LOCAL_NODE_ID, generator.generateKeyPair().getPublic());

    directory = new InMemoryCandidateDirectory();
    final DiscoveryProperties properties = new DiscoveryProperties();
    properties.setMaxRatio(1.25d);
    properties.setMaxCandidatesLimit(50);
    properties.setFailureDomainFilterEnabled(true);
    discoveryService = new DiscoveryService(keyRegistry, directory, properties);
    cache = new DiscoveryQueryCache(clock, 30L);
  }

  private FileContentDistributionService buildSut(
      final FileContentDistributionService.DiscoveryWiring wiring) {
    return new FileContentDistributionService(
        rsCodec,
        rsCodec,
        new InMemoryRsIntegrityVerifier(),
        manifestPort,
        placementPort,
        remoteClient,
        quotaPort,
        clock,
        SCHEME,
        MAX_BYTES,
        128,
        List.of(),
        null,
        null,
        null,
        wiring);
  }

  @Test
  void distributeUpload_callsDiscoveryServiceAndDispatchesToReturnedBaseUrls() {
    directory.add(profile("peer-1", "zone-a/rack-1", "http://peer1:8080"));
    directory.add(profile("peer-2", "zone-a/rack-2", "http://peer2:8080"));
    directory.add(profile("peer-3", "zone-a/rack-3", "http://peer3:8080"));

    final FileContentDistributionService sut =
        buildSut(
            new FileContentDistributionService.DiscoveryWiring(
                new FakeRemoteQueryClient(discoveryService),
                List.of("http://supernode-1:8080"),
                cache,
                LOCAL_NODE_ID,
                "zone-a",
                1024L,
                1.0d,
                "",
                3,
                true));

    final byte[] payload = randomBytes(16);
    sut.distributeUpload(USERNAME, activeEntryFor(payload), payload);

    final Set<String> targets = remoteClient.distinctTargets();
    assertEquals(3, targets.size());
    assertTrue(targets.contains("http://peer1:8080"));
    assertTrue(targets.contains("http://peer2:8080"));
    assertTrue(targets.contains("http://peer3:8080"));
  }

  @Test
  void distributeUpload_dedupesByBaseUrlEvenIfTwoNodeIdsShareUrl() {
    directory.add(profile("peer-1a", "zone-a/rack-1", "http://shared:8080"));
    directory.add(profile("peer-1b", "zone-a/rack-1", "http://shared:8080"));
    directory.add(profile("peer-2", "zone-a/rack-2", "http://peer2:8080"));
    directory.add(profile("peer-3", "zone-a/rack-3", "http://peer3:8080"));

    final FileContentDistributionService sut =
        buildSut(
            new FileContentDistributionService.DiscoveryWiring(
                new FakeRemoteQueryClient(discoveryService),
                List.of("http://supernode-1:8080"),
                cache,
                LOCAL_NODE_ID,
                "zone-a",
                1024L,
                1.0d,
                "",
                3,
                true));

    final byte[] payload = randomBytes(16);
    sut.distributeUpload(USERNAME, activeEntryFor(payload), payload);

    final Set<String> targets = remoteClient.distinctTargets();
    assertEquals(3, targets.size());
    assertTrue(targets.contains("http://shared:8080"));
    assertTrue(targets.contains("http://peer2:8080"));
    assertTrue(targets.contains("http://peer3:8080"));
  }

  @Test
  void distributeUpload_throwsInsufficientCustodiansAfterRetries() {
    directory.add(profile("peer-1", "zone-a/rack-1", "http://peer1:8080"));
    directory.add(profile("peer-2", "zone-a/rack-2", "http://peer2:8080"));

    final FileContentDistributionService sut =
        buildSut(
            new FileContentDistributionService.DiscoveryWiring(
                new FakeRemoteQueryClient(discoveryService),
                List.of("http://supernode-1:8080"),
                cache,
                LOCAL_NODE_ID,
                "zone-a",
                1024L,
                1.0d,
                "",
                3,
                true));

    final byte[] payload = randomBytes(16);
    assertThrows(
        InsufficientCustodiansException.class,
        () -> sut.distributeUpload(USERNAME, activeEntryFor(payload), payload));
  }

  @Test
  void distributeUpload_filtersSelfWhenAllowSelfCandidateFalse() {
    directory.add(profile(LOCAL_NODE_ID, "zone-a/rack-1", "http://self:8080"));
    directory.add(profile("peer-1", "zone-a/rack-2", "http://peer1:8080"));
    directory.add(profile("peer-2", "zone-a/rack-3", "http://peer2:8080"));
    directory.add(profile("peer-3", "zone-a/rack-4", "http://peer3:8080"));

    final FileContentDistributionService sut =
        buildSut(
            new FileContentDistributionService.DiscoveryWiring(
                new FakeRemoteQueryClient(discoveryService),
                List.of("http://supernode-1:8080"),
                cache,
                LOCAL_NODE_ID,
                "zone-a",
                1024L,
                1.0d,
                "",
                3,
                false));

    final byte[] payload = randomBytes(16);
    sut.distributeUpload(USERNAME, activeEntryFor(payload), payload);

    final Set<String> targets = remoteClient.distinctTargets();
    assertEquals(3, targets.size());
    assertFalse(targets.contains("http://self:8080"));
  }

  // ---------- failover round-robin entre supernodos ----------

  @Test
  void distributeUpload_failoverWhenFirstSupernodeUnreachable() {
    directory.add(profile("peer-1", "zone-a/rack-1", "http://peer1:8080"));
    directory.add(profile("peer-2", "zone-a/rack-2", "http://peer2:8080"));
    directory.add(profile("peer-3", "zone-a/rack-3", "http://peer3:8080"));

    // primer supernodo siempre cae con DiscoveryUnreachableException; segundo OK.
    final Map<String, RemoteDiscoveryQueryClientPort> behaviour = new LinkedHashMap<>();
    behaviour.put(
        "http://supernode-down:8080",
        (baseUrl, req) -> {
          throw new DiscoveryUnreachableException(baseUrl, "simulated outage");
        });
    behaviour.put("http://supernode-up:8080", new FakeRemoteQueryClient(discoveryService));

    final FileContentDistributionService sut =
        buildSut(
            new FileContentDistributionService.DiscoveryWiring(
                new MultiSupernodeRouter(behaviour),
                // ambos supernodos en la lista; el shuffle es no-determinista pero el test
                // siempre tiene que funcionar (haga el orden que haga). El primero que falle se
                // failover-ea al segundo.
                List.of("http://supernode-down:8080", "http://supernode-up:8080"),
                cache,
                LOCAL_NODE_ID,
                "zone-a",
                1024L,
                1.0d,
                "",
                3,
                true));

    final byte[] payload = randomBytes(16);
    sut.distributeUpload(USERNAME, activeEntryFor(payload), payload);

    final Set<String> targets = remoteClient.distinctTargets();
    assertEquals(3, targets.size());
  }

  @Test
  void distributeUpload_throwsDiscoveryUnreachableWhenAllSupernodesDown() {
    directory.add(profile("peer-1", "zone-a/rack-1", "http://peer1:8080"));
    directory.add(profile("peer-2", "zone-a/rack-2", "http://peer2:8080"));
    directory.add(profile("peer-3", "zone-a/rack-3", "http://peer3:8080"));

    // ambos supernodos caen.
    final RemoteDiscoveryQueryClientPort allDown =
        (baseUrl, req) -> {
          throw new DiscoveryUnreachableException(baseUrl, "all supernodes down");
        };

    final FileContentDistributionService sut =
        buildSut(
            new FileContentDistributionService.DiscoveryWiring(
                allDown,
                List.of("http://supernode-a:8080", "http://supernode-b:8080"),
                cache,
                LOCAL_NODE_ID,
                "zone-a",
                1024L,
                1.0d,
                "",
                3,
                true));

    final byte[] payload = randomBytes(16);
    assertThrows(
        DiscoveryUnreachableException.class,
        () -> sut.distributeUpload(USERNAME, activeEntryFor(payload), payload));
  }

  private FsEntry activeEntryFor(final byte[] content) {
    return new FsEntry(
        "entry-disc",
        USERNAME,
        "/disc.bin",
        FsEntryType.FILE,
        (long) content.length,
        sha256Hex(content),
        "11111111-1111-1111-1111-111111111111",
        1L,
        Instant.parse("2026-05-03T10:00:00Z"),
        false);
  }

  private static DiscoveryCandidateProfile profile(
      final String nodeId, final String failureDomain, final String baseUrl) {
    return new DiscoveryCandidateProfile(nodeId, failureDomain, baseUrl, 1024L, Set.of(1024L));
  }

  private static byte[] randomBytes(final int n) {
    final byte[] b = new byte[n];
    for (int i = 0; i < n; i++) {
      b[i] = (byte) (i + 1);
    }
    return b;
  }

  private static String sha256Hex(final byte[] payload) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(payload));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** Recording fragment client capturing every storeFragment invocation. */
  private static final class RecordingRemoteClient implements RemoteFragmentDistributionClientPort {
    private final List<String> orderedTargets = new ArrayList<>();
    private final Map<String, byte[]> stores = new HashMap<>();

    @Override
    public void storeFragment(
        final String custodianBaseUrl,
        final String fragmentId,
        final String agreementId,
        final byte[] payload,
        final String checksumAlgorithm,
        final String checksumHex,
        final Long custodySeconds) {
      orderedTargets.add(custodianBaseUrl);
      stores.put(custodianBaseUrl + "::" + fragmentId, payload.clone());
    }

    @Override
    public byte[] fetchFragment(final String custodianBaseUrl, final String fragmentId) {
      final byte[] payload = stores.get(custodianBaseUrl + "::" + fragmentId);
      if (payload == null) {
        throw new IllegalStateException("fragment not stored: " + fragmentId);
      }
      return payload.clone();
    }

    Set<String> distinctTargets() {
      return new LinkedHashSet<>(orderedTargets);
    }
  }

  /**
   * Fake remote discovery query client for tests. Delegates to a local {@link DiscoveryService}
   * (los tests del happy path mantienen el comportamiento previo del modelo replicado: el supernodo
   * "remoto" es el directorio del test).
   */
  private static final class FakeRemoteQueryClient implements RemoteDiscoveryQueryClientPort {
    private final DiscoveryService delegate;

    FakeRemoteQueryClient(final DiscoveryService delegate) {
      this.delegate = delegate;
    }

    @Override
    public DiscoveryResponse discover(
        final String supernodeBaseUrl, final DiscoveryRequest request) {
      return delegate.discover(request);
    }
  }

  /**
   * Routes la discover call al port asociado al baseUrl. Permite simular failover: un baseUrl lanza
   * {@link DiscoveryUnreachableException} y otro responde OK.
   */
  private static final class MultiSupernodeRouter implements RemoteDiscoveryQueryClientPort {
    private final Map<String, RemoteDiscoveryQueryClientPort> behaviour;

    MultiSupernodeRouter(final Map<String, RemoteDiscoveryQueryClientPort> behaviour) {
      this.behaviour = behaviour;
    }

    @Override
    public DiscoveryResponse discover(
        final String supernodeBaseUrl, final DiscoveryRequest request) {
      final RemoteDiscoveryQueryClientPort target = behaviour.get(supernodeBaseUrl);
      if (target == null) {
        throw new DiscoveryUnreachableException(supernodeBaseUrl, "no behaviour configured");
      }
      return target.discover(supernodeBaseUrl, request);
    }
  }

  /** Test double for the candidate directory port. */
  private static final class InMemoryCandidateDirectory implements DiscoveryCandidateDirectoryPort {
    private final Map<String, DiscoveryCandidateProfile> rows = new LinkedHashMap<>();

    void add(final DiscoveryCandidateProfile profile) {
      rows.put(profile.nodeId(), profile);
    }

    @Override
    public List<DiscoveryCandidateProfile> findActiveCandidates() {
      return List.copyOf(rows.values());
    }

    @Override
    public long countActiveCandidates() {
      return rows.size();
    }

    @Override
    public void upsertCandidate(final DiscoveryCandidateProfile profile) {
      rows.put(profile.nodeId(), profile);
    }

    @Override
    public void removeCandidate(final String nodeId) {
      rows.remove(nodeId);
    }

    @Override
    public int deleteStale(final Instant staleBefore) {
      return 0;
    }
  }
}
