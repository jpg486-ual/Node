package es.ual.node.sync.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Unit tests for {@link SyncEventService}. */
class SyncEventServiceTest {

  private static final long EXPECTED_EMITTER_TIMEOUT_MILLIS = 30L * 60L * 1000L;

  @Test
  void subscribe_returnsEmitterWithThirtyMinuteTimeout() {
    final RecordingService service = new RecordingService();

    final SseEmitter emitter = service.subscribe("alice", 0L);

    assertThat(emitter.getTimeout()).isEqualTo(EXPECTED_EMITTER_TIMEOUT_MILLIS);
  }

  @Test
  void subscribe_emitsConnectedEventWithCurrentCursor() {
    final RecordingService service = new RecordingService();

    service.subscribe("alice", 42L);

    final List<CapturingSseEmitter> emitters = service.lastEmitters();
    assertThat(emitters).hasSize(1);
    final List<SyncEventPayload> payloads = emitters.get(0).capturedPayloads();
    assertThat(payloads).hasSize(1);
    final SyncEventPayload connected = payloads.get(0);
    assertThat(connected.type()).isEqualTo("connected");
    assertThat(connected.cursor()).isEqualTo(42L);
    assertThat(connected.entryId()).isNull();
    assertThat(connected.occurredAt()).isNotNull();
  }

  @Test
  void subscribe_registersEmitterInUserBucket() {
    final RecordingService service = new RecordingService();

    service.subscribe("alice", 0L);
    service.subscribe("alice", 0L);

    assertThat(service.lastEmitters()).hasSize(2);
  }

  @Test
  void publish_broadcastsToAllEmittersOfSameUser() {
    final RecordingService service = new RecordingService();
    service.subscribe("alice", 0L);
    service.subscribe("alice", 0L);

    service.publish("alice", "fs.entry.created", 7L, "entry-1");

    for (CapturingSseEmitter emitter : service.lastEmitters()) {
      final List<SyncEventPayload> payloads = emitter.capturedPayloads();
      assertThat(payloads).hasSize(2);
      assertThat(payloads.get(1).type()).isEqualTo("fs.entry.created");
      assertThat(payloads.get(1).cursor()).isEqualTo(7L);
      assertThat(payloads.get(1).entryId()).isEqualTo("entry-1");
    }
  }

  @Test
  void publish_isolatedAcrossUsers() {
    final RecordingService service = new RecordingService();
    service.subscribe("alice", 0L);
    final CapturingSseEmitter aliceEmitter = service.lastEmitters().get(0);
    service.subscribe("bob", 0L);
    final CapturingSseEmitter bobEmitter = service.lastEmitters().get(1);

    service.publish("alice", "fs.entry.created", 1L, "alice-entry");

    assertThat(aliceEmitter.capturedPayloads())
        .extracting(SyncEventPayload::type)
        .containsExactly("connected", "fs.entry.created");
    assertThat(bobEmitter.capturedPayloads())
        .extracting(SyncEventPayload::type)
        .containsExactly("connected");
  }

  @Test
  void publish_noOpWhenNoEmittersForUser() {
    final RecordingService service = new RecordingService();

    assertThatCode(() -> service.publish("ghost", "fs.entry.created", 0L, "x"))
        .doesNotThrowAnyException();
  }

  @Test
  void publish_serializesEventTypeAndCursor() {
    final RecordingService service = new RecordingService();
    service.subscribe("alice", 0L);

    service.publish("alice", "fs.entry.deleted", null, null);

    final SyncEventPayload event = service.lastEmitters().get(0).capturedPayloads().get(1);
    assertThat(event.type()).isEqualTo("fs.entry.deleted");
    assertThat(event.cursor()).isNull();
    assertThat(event.entryId()).isNull();
    assertThat(event.occurredAt()).isNotNull();
  }

  @Test
  void onCompletionHandlerUnsubscribesEmitter() {
    final RecordingService service = new RecordingService();
    service.subscribe("alice", 0L);
    final CapturingSseEmitter emitter = service.lastEmitters().get(0);

    emitter.simulateCompletion();

    service.publish("alice", "fs.entry.created", 1L, "later");
    assertThat(emitter.capturedPayloads())
        .extracting(SyncEventPayload::type)
        .containsExactly("connected");
    assertThat(service.userIsTracked("alice")).isFalse();
  }

  @Test
  void onTimeoutHandlerUnsubscribesEmitter() {
    final RecordingService service = new RecordingService();
    service.subscribe("alice", 0L);
    final CapturingSseEmitter emitter = service.lastEmitters().get(0);

    emitter.simulateTimeout();

    assertThat(service.userIsTracked("alice")).isFalse();
  }

  @Test
  void onErrorHandlerUnsubscribesEmitter() {
    final RecordingService service = new RecordingService();
    service.subscribe("alice", 0L);
    final CapturingSseEmitter emitter = service.lastEmitters().get(0);

    emitter.simulateError(new RuntimeException("boom"));

    assertThat(service.userIsTracked("alice")).isFalse();
  }

  @Test
  void publish_handlesIOExceptionByUnsubscribingFailingEmitter() {
    final RecordingService service = new RecordingService();
    service.subscribe("alice", 0L);
    final CapturingSseEmitter healthy = service.lastEmitters().get(0);
    service.subscribe("alice", 0L);
    final CapturingSseEmitter broken = service.lastEmitters().get(1);

    broken.throwOnSend = true;
    service.publish("alice", "fs.entry.created", 1L, "id");

    assertThat(healthy.capturedPayloads()).hasSize(2);
    assertThat(broken.capturedPayloads()).hasSize(1);
    assertThat(service.userIsTracked("alice")).isTrue();
  }

  // ---------- Test helpers ----------

  private static final class RecordingService extends SyncEventService {
    private final List<CapturingSseEmitter> emitters = new ArrayList<>();

    @Override
    protected SseEmitter createEmitter(final long timeoutMillis) {
      final CapturingSseEmitter emitter = new CapturingSseEmitter(timeoutMillis);
      emitters.add(emitter);
      return emitter;
    }

    List<CapturingSseEmitter> lastEmitters() {
      return List.copyOf(emitters);
    }

    boolean userIsTracked(final String username) {
      try {
        final java.lang.reflect.Field field =
            SyncEventService.class.getDeclaredField("emittersByUsername");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        final java.util.Map<String, ?> map = (java.util.Map<String, ?>) field.get(this);
        return map.containsKey(username);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("emittersByUsername field not accessible", ex);
      }
    }
  }

  private static final class CapturingSseEmitter extends SseEmitter {
    final List<Set<ResponseBodyEmitter.DataWithMediaType>> sent = new ArrayList<>();
    boolean throwOnSend;
    private Runnable timeoutHandler;
    private Runnable completionHandler;
    private Consumer<Throwable> errorHandler;

    CapturingSseEmitter(final long timeout) {
      super(timeout);
    }

    @Override
    public synchronized void send(final SseEventBuilder builder) throws IOException {
      if (throwOnSend) {
        throw new IOException("simulated send failure");
      }
      sent.add(new HashSet<>(builder.build()));
    }

    @Override
    public synchronized void onTimeout(final Runnable callback) {
      this.timeoutHandler = callback;
      super.onTimeout(callback);
    }

    @Override
    public synchronized void onCompletion(final Runnable callback) {
      this.completionHandler = callback;
      super.onCompletion(callback);
    }

    @Override
    public synchronized void onError(final Consumer<Throwable> callback) {
      this.errorHandler = callback;
      super.onError(callback);
    }

    void simulateCompletion() {
      if (completionHandler != null) {
        completionHandler.run();
      }
    }

    void simulateTimeout() {
      if (timeoutHandler != null) {
        timeoutHandler.run();
      }
    }

    void simulateError(final Throwable throwable) {
      if (errorHandler != null) {
        errorHandler.accept(throwable);
      }
    }

    List<SyncEventPayload> capturedPayloads() {
      final List<SyncEventPayload> result = new ArrayList<>();
      for (Set<ResponseBodyEmitter.DataWithMediaType> items : sent) {
        for (ResponseBodyEmitter.DataWithMediaType item : items) {
          if (item.getData() instanceof SyncEventPayload payload) {
            result.add(payload);
          }
        }
      }
      return result;
    }
  }
}
