package es.ual.node.recovery.application;

import es.ual.node.recovery.domain.RecoveryOrphanFragment;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPayloadPort;
import es.ual.node.recovery.ports.out.RecoveryOrphanFragmentPort;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tutor-side service que implementa el flujo de claim+ACK del origen recovered. Dos pasos:
 *
 * <ol>
 *   <li>{@code POST /recovery/orphan-fragments/{fragmentId}/claim}: devuelve los bytes del fragment
 *       (signed, owner-only). El tutor NO borra todavía. Protege contra descarga interrumpida.
 *   <li>{@code POST /recovery/orphan-fragments/{fragmentId}/ack}: el origen confirma descarga
 *       completa. Solo entonces el tutor borra metadata + payload.
 * </ol>
 *
 * <p>Authorization: el caller {@code X-Node-Id} debe matchear el {@code requesterNodeId} del orphan
 * row — cross-owner claim rejected.
 */
public class RecoveryOrphanClaimService {

  private static final Logger LOGGER = LoggerFactory.getLogger(RecoveryOrphanClaimService.class);

  private final RecoveryOrphanFragmentPort orphanPort;
  private final RecoveryOrphanFragmentPayloadPort payloadPort;

  /** Creates service. */
  public RecoveryOrphanClaimService(
      final RecoveryOrphanFragmentPort orphanPort,
      final RecoveryOrphanFragmentPayloadPort payloadPort) {
    if (orphanPort == null || payloadPort == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.orphanPort = orphanPort;
    this.payloadPort = payloadPort;
  }

  /**
   * Claim step: returns metadata + bytes for the orphan fragment. Does NOT delete. The caller must
   * confirm with {@link #ack(String, String)}.
   *
   * @param fragmentId fragment identifier
   * @param callerNodeId caller node id (from signature)
   * @return orphan + bytes
   * @throws NoSuchElementException when no orphan fragment exists
   * @throws SecurityException when caller does not own the orphan
   */
  @Transactional(readOnly = true)
  public ClaimResult claim(final String fragmentId, final String callerNodeId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    if (callerNodeId == null || callerNodeId.isBlank()) {
      throw new IllegalArgumentException("callerNodeId must not be blank");
    }
    final String normalizedId = fragmentId.trim();
    final String caller = callerNodeId.trim();
    final RecoveryOrphanFragment orphan =
        orphanPort
            .findByFragmentId(normalizedId)
            .orElseThrow(() -> new NoSuchElementException("orphan not found: " + normalizedId));
    if (!orphan.requesterNodeId().equals(caller)) {
      throw new SecurityException("caller " + caller + " does not own orphan " + normalizedId);
    }
    final byte[] bytes =
        payloadPort
            .findByFragmentId(normalizedId)
            .orElseThrow(
                () -> new NoSuchElementException("orphan payload missing: " + normalizedId));
    LOGGER
        .atInfo()
        .setMessage("Orphan fragment claimed by recovered origin")
        .addKeyValue("event", "ORPHAN_CLAIMED")
        .addKeyValue("fragmentId", normalizedId)
        .addKeyValue("callerNodeId", caller)
        .log();
    return new ClaimResult(orphan, bytes);
  }

  /**
   * ACK step: caller confirms successful download, tutor deletes metadata + payload internally.
   * Idempotent: repeated ACKs for an absent orphan return without raising.
   *
   * @param fragmentId fragment identifier
   * @param callerNodeId caller node id (from signature)
   * @throws SecurityException when caller does not own the orphan and the orphan still exists
   */
  @Transactional
  public void ack(final String fragmentId, final String callerNodeId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    if (callerNodeId == null || callerNodeId.isBlank()) {
      throw new IllegalArgumentException("callerNodeId must not be blank");
    }
    final String normalizedId = fragmentId.trim();
    final String caller = callerNodeId.trim();
    final RecoveryOrphanFragment orphan = orphanPort.findByFragmentId(normalizedId).orElse(null);
    if (orphan == null) {
      LOGGER
          .atDebug()
          .setMessage("Orphan ACK ignored — already deleted")
          .addKeyValue("fragmentId", normalizedId)
          .log();
      return;
    }
    if (!orphan.requesterNodeId().equals(caller)) {
      throw new SecurityException("caller " + caller + " does not own orphan " + normalizedId);
    }
    // Orden de delete invertido (payload primero, orphan después). Evita
    // StaleObjectStateException con la FK V8 ON DELETE CASCADE: si borrásemos el orphan primero,
    // el cascade marcaría el payload como deleted en la misma sesión Hibernate y la siguiente
    // payloadPort.deleteByFragmentId() fallaría al flush. Borrar payload primero es seguro
    // (no hay dependencia inversa); el cascade del orphan delete posterior es no-op silencioso
    // si la row payload ya no existe.
    payloadPort.deleteByFragmentId(normalizedId);
    orphanPort.deleteByFragmentId(normalizedId);
    LOGGER
        .atInfo()
        .setMessage("Orphan fragment ACKed and deleted")
        .addKeyValue("event", "ORPHAN_ACKED_AND_DELETED")
        .addKeyValue("fragmentId", normalizedId)
        .addKeyValue("callerNodeId", caller)
        .log();
  }

  /**
   * Admin-side delete. Hard-deletes orphan + payload. Idempotent.
   *
   * @param fragmentId fragment identifier
   * @return {@code true} if a row was deleted
   */
  @Transactional
  public boolean adminDelete(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    final String normalizedId = fragmentId.trim();
    final boolean exists = orphanPort.findByFragmentId(normalizedId).isPresent();
    // Mismo orden invertido que en ack() — payload primero, orphan después, evita
    // StaleObjectStateException con FK CASCADE.
    payloadPort.deleteByFragmentId(normalizedId);
    orphanPort.deleteByFragmentId(normalizedId);
    LOGGER
        .atInfo()
        .setMessage("Orphan fragment admin-deleted")
        .addKeyValue("event", "ORPHAN_ADMIN_DELETED")
        .addKeyValue("fragmentId", normalizedId)
        .addKeyValue("existed", exists)
        .log();
    return exists;
  }

  /** Claim result payload. */
  public record ClaimResult(RecoveryOrphanFragment orphan, byte[] payload) {}
}
