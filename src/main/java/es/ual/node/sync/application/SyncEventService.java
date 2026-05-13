package es.ual.node.sync.application;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Runtime in-memory SSE event hub for user sync channels. */
public class SyncEventService {

  private static final long EMITTER_TIMEOUT_MILLIS = 30L * 60L * 1000L;

  private final Map<String, List<SseEmitter>> emittersByUsername = new ConcurrentHashMap<>();

  /**
   * Subscribes current user to sync event stream.
   *
   * @param username username
   * @param currentCursor latest known cursor
   * @return emitter
   */
  public SseEmitter subscribe(final String username, final long currentCursor) {
    final SseEmitter emitter = createEmitter(EMITTER_TIMEOUT_MILLIS);
    emittersByUsername.computeIfAbsent(username, key -> new CopyOnWriteArrayList<>()).add(emitter);

    emitter.onCompletion(() -> removeEmitter(username, emitter));
    emitter.onTimeout(() -> removeEmitter(username, emitter));
    emitter.onError(ex -> removeEmitter(username, emitter));

    publishToSingleEmitter(
        username, emitter, new SyncEventPayload("connected", currentCursor, null, Instant.now()));
    return emitter;
  }

  /**
   * Creates SSE emitter for subscription (overridable for testing — Beck TDD ch. 23).
   *
   * @param timeoutMillis emitter timeout in milliseconds
   * @return new emitter
   */
  protected SseEmitter createEmitter(final long timeoutMillis) {
    return new SseEmitter(timeoutMillis);
  }

  /**
   * Publishes event to all connected clients for user.
   *
   * @param username username
   * @param eventType event type
   * @param cursor sync cursor
   * @param entryId entry id when available
   */
  public void publish(
      final String username, final String eventType, final Long cursor, final String entryId) {
    final List<SseEmitter> emitters = emittersByUsername.get(username);
    if (emitters == null || emitters.isEmpty()) {
      return;
    }

    final SyncEventPayload event = new SyncEventPayload(eventType, cursor, entryId, Instant.now());
    for (SseEmitter emitter : emitters) {
      publishToSingleEmitter(username, emitter, event);
    }
  }

  private void publishToSingleEmitter(
      final String username, final SseEmitter emitter, final SyncEventPayload event) {
    try {
      emitter.send(SseEmitter.event().name(event.type()).data(event));
    } catch (IOException exception) {
      removeEmitter(username, emitter);
    }
  }

  private void removeEmitter(final String username, final SseEmitter emitter) {
    final List<SseEmitter> emitters = emittersByUsername.get(username);
    if (emitters == null) {
      return;
    }
    emitters.remove(emitter);
    if (emitters.isEmpty()) {
      emittersByUsername.remove(username);
    }
  }
}
