package es.ual.node.custodyliveness.adapters.in.web;

import java.util.List;

/**
 * Respuesta del origen al probe del custodian. El origen indica al custodian qué fragments debe
 * conservar: cualquier fragment listado en la request que NO aparezca en {@code keepFragmentIds}
 * debe ser purgado por el custodian (hard-delete + cancel agreement).
 *
 * <p>El origen también anuncia su propio {@code tutorBaseUrl} (su {@code
 * node.topology.tutorBaseUrl}) para que el custodian lo aprenda y lo vincule al {@code
 * requesterNodeId}: así, si el origen cae, el custodian devuelve los fragments al tutor <em>de ese
 * origen</em> (no al suyo local). Opcional: {@code null} en orígenes que no lo anuncian
 * (retrocompatibilidad).
 *
 * @param keepFragmentIds whitelist de fragments a conservar
 * @param tutorBaseUrl tutor base URL del origen (opcional, puede ser {@code null})
 */
public record KeepListResponsePayload(List<String> keepFragmentIds, String tutorBaseUrl) {

  public KeepListResponsePayload {
    if (keepFragmentIds == null) {
      keepFragmentIds = List.of();
    } else {
      keepFragmentIds = List.copyOf(keepFragmentIds);
    }
  }

  /** Backward-compatible factory without a tutor advertisement. */
  public KeepListResponsePayload(final List<String> keepFragmentIds) {
    this(keepFragmentIds, null);
  }
}
