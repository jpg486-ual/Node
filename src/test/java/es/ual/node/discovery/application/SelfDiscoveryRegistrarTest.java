package es.ual.node.discovery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import es.ual.node.discovery.ports.out.RemoteDiscoveryCandidateClientPort;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SelfDiscoveryRegistrar}. */
class SelfDiscoveryRegistrarTest {

  private static final String NODE_ID = "node-test-id";
  private static final String FAILURE_DOMAIN = "zone-a/rack-1";
  private static final String LOCAL_BASE_URL = "http://self:8080";

  private RecordingRemoteClient remoteClient;
  private NodeIdentityContext localIdentity;

  @BeforeEach
  void setUp() throws Exception {
    remoteClient = new RecordingRemoteClient();
    localIdentity = nodeIdentity(NODE_ID);
  }

  @Test
  void rejectsNullDependencies() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SelfDiscoveryRegistrar(
                null, remoteClient, List.of("http://node1:8080"), FAILURE_DOMAIN, LOCAL_BASE_URL));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SelfDiscoveryRegistrar(
                localIdentity, null, List.of("http://node1:8080"), FAILURE_DOMAIN, LOCAL_BASE_URL));
  }

  @Test
  void registersOnEverySupernodeWhenAllSucceed() {
    final SelfDiscoveryRegistrar sut =
        new SelfDiscoveryRegistrar(
            localIdentity,
            remoteClient,
            List.of("http://node1:8080", "http://node2:8080", "http://node3:8080"),
            FAILURE_DOMAIN,
            LOCAL_BASE_URL);

    final int succeeded = sut.registerSelf();

    assertEquals(3, succeeded);
    assertEquals(3, remoteClient.calls.size());
    assertEquals("http://node1:8080", remoteClient.calls.get(0).baseUrl);
    assertEquals(NODE_ID, remoteClient.calls.get(0).profile.nodeId());
    assertEquals(FAILURE_DOMAIN, remoteClient.calls.get(0).profile.failureDomain());
    assertEquals(LOCAL_BASE_URL, remoteClient.calls.get(0).profile.baseUrl());
  }

  @Test
  void continuesWhenOneSupernodeFails() {
    remoteClient.failOn = "http://node2:8080";
    final SelfDiscoveryRegistrar sut =
        new SelfDiscoveryRegistrar(
            localIdentity,
            remoteClient,
            List.of("http://node1:8080", "http://node2:8080", "http://node3:8080"),
            FAILURE_DOMAIN,
            LOCAL_BASE_URL);

    final int succeeded = sut.registerSelf();

    assertEquals(2, succeeded);
    assertEquals(3, remoteClient.calls.size());
  }

  @Test
  void skipsRegistrationWhenFailureDomainBlank() {
    final SelfDiscoveryRegistrar sut =
        new SelfDiscoveryRegistrar(
            localIdentity, remoteClient, List.of("http://node1:8080"), "  ", LOCAL_BASE_URL);

    final int succeeded = sut.registerSelf();

    assertEquals(0, succeeded);
    assertEquals(0, remoteClient.calls.size());
  }

  @Test
  void skipsRegistrationWhenLocalBaseUrlBlank() {
    final SelfDiscoveryRegistrar sut =
        new SelfDiscoveryRegistrar(
            localIdentity, remoteClient, List.of("http://node1:8080"), FAILURE_DOMAIN, "  ");

    final int succeeded = sut.registerSelf();

    assertEquals(0, succeeded);
    assertEquals(0, remoteClient.calls.size());
  }

  @Test
  void skipsRegistrationWhenSupernodesEmpty() {
    final SelfDiscoveryRegistrar sut =
        new SelfDiscoveryRegistrar(
            localIdentity, remoteClient, List.of(), FAILURE_DOMAIN, LOCAL_BASE_URL);

    final int succeeded = sut.registerSelf();

    assertEquals(0, succeeded);
    assertEquals(0, remoteClient.calls.size());
  }

  @Test
  void skipsRegistrationWhenSupernodesNull() {
    final SelfDiscoveryRegistrar sut =
        new SelfDiscoveryRegistrar(
            localIdentity, remoteClient, null, FAILURE_DOMAIN, LOCAL_BASE_URL);

    final int succeeded = sut.registerSelf();

    assertEquals(0, succeeded);
    assertEquals(0, remoteClient.calls.size());
  }

  private static NodeIdentityContext nodeIdentity(final String nodeId) throws Exception {
    final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(256);
    final KeyPair keyPair = generator.generateKeyPair();
    return new NodeIdentityContext(nodeId, keyPair.getPublic(), keyPair.getPrivate());
  }

  private static final class RecordingRemoteClient implements RemoteDiscoveryCandidateClientPort {
    private final List<RecordedCall> calls = new ArrayList<>();
    private String failOn;

    @Override
    public void upsertCandidate(final String baseUrl, final DiscoveryCandidateProfile profile) {
      calls.add(new RecordedCall(baseUrl, profile));
      if (baseUrl.equals(failOn)) {
        throw new IllegalStateException("simulated upsert failure for " + baseUrl);
      }
    }
  }

  private static final class RecordedCall {
    private final String baseUrl;
    private final DiscoveryCandidateProfile profile;

    private RecordedCall(final String baseUrl, final DiscoveryCandidateProfile profile) {
      this.baseUrl = baseUrl;
      this.profile = profile;
    }
  }
}
