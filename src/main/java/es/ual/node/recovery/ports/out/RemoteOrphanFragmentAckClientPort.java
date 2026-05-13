package es.ual.node.recovery.ports.out;

/**
 * Outbound signed-HTTP port para que el origen invoque {@code POST
 * /recovery/orphan-fragments/{fragmentId}/ack} en su tutor. Tras un re-upload exitoso del archivo
 * (post-restore en strategy {@code BYTES_FROM_TUTOR}), los fragments huérfanos del tutor que
 * referencian al {@code oldFileId} ya no son necesarios, el origen le dice al tutor "ya no los
 * necesito" y el tutor borra metadata + payload.
 *
 * <p>El endpoint del tutor verifica ownership ({@code X-Node-Id == requesterNodeId}) e idempotencia
 * (re-ack sobre orphan absent = no-op). El cliente no necesita coordinar claim previo: ack-only
 * está soportado.
 *
 * @see es.ual.node.recovery.adapters.in.web.RecoveryOrphanFragmentClaimController#ack
 * @see es.ual.node.recovery.application.RecoveryOrphanClaimService#ack
 */
public interface RemoteOrphanFragmentAckClientPort {

  /**
   * ACK al tutor para que borre el orphan fragment + payload asociados.
   *
   * @param fragmentId fragment identifier
   * @param tutorBaseUrl base URL del tutor (e.g. {@code http://node2:8080})
   * @throws IllegalArgumentException si algún argumento es blank/null
   * @throws IllegalStateException si el HTTP exchange falla o devuelve status no esperado (excluido
   *     404 que se trata como idempotente y NO lanza)
   */
  void ack(String fragmentId, String tutorBaseUrl);
}
