package es.ual.node.discovery.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import es.ual.node.discovery.ports.out.RemoteDiscoveryCandidateClientPort;
import es.ual.node.identitysecurity.application.NodeIdentityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SelfDiscoveryRenewalWorker}. */
class SelfDiscoveryRenewalWorkerTest {

  @Test
  void rejectsNullRegistrar() {
    assertThrows(IllegalArgumentException.class, () -> new SelfDiscoveryRenewalWorker(null));
  }

  @Test
  void renewInvokesRegistrar() throws Exception {
    final RecordingRegistrar registrar = new RecordingRegistrar(2);
    final SelfDiscoveryRenewalWorker worker = new SelfDiscoveryRenewalWorker(registrar);

    worker.renew();
    worker.renew();

    assertEquals(2, registrar.invocations);
  }

  @Test
  void renewSwallowsRegistrarExceptionsSoSchedulerStaysAlive() throws Exception {
    final SelfDiscoveryRenewalWorker worker =
        new SelfDiscoveryRenewalWorker(new ThrowingRegistrar());

    assertDoesNotThrow(worker::renew);
  }

  private static NodeIdentityContext buildIdentity() throws Exception {
    final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(256);
    final KeyPair keyPair = generator.generateKeyPair();
    return new NodeIdentityContext("test-node", keyPair.getPublic(), keyPair.getPrivate());
  }

  /** Test double — overrides {@code registerSelf} to count invocations and skip side effects. */
  private static final class RecordingRegistrar extends SelfDiscoveryRegistrar {
    private final int returnValue;
    private int invocations;

    RecordingRegistrar(final int returnValue) throws Exception {
      super(buildIdentity(), noopRemote(), List.of(), "zone-a/rack-1", "http://x:8080");
      this.returnValue = returnValue;
    }

    @Override
    public int registerSelf() {
      invocations++;
      return returnValue;
    }
  }

  private static final class ThrowingRegistrar extends SelfDiscoveryRegistrar {
    ThrowingRegistrar() throws Exception {
      super(buildIdentity(), noopRemote(), List.of(), "zone-a/rack-1", "http://x:8080");
    }

    @Override
    public int registerSelf() {
      throw new IllegalStateException("simulated failure");
    }
  }

  private static RemoteDiscoveryCandidateClientPort noopRemote() {
    return (baseUrl, profile) -> {};
  }
}
