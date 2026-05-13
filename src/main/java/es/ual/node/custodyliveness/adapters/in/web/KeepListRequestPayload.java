package es.ual.node.custodyliveness.adapters.in.web;

import java.util.List;

/**
 * Body del probe iniciado por el custodian al origen. El custodian envía la lista de fragments que
 * tiene custodiados para el {@code requesterNodeId} y pregunta cuáles debe conservar.
 *
 * <p>El origen responde con {@link KeepListResponsePayload#keepFragmentIds()}. El custodian purga
 * (hard-delete + cancel agreement) cualquier fragment listado en la request que NO aparezca en la
 * respuesta.
 *
 * @param requesterNodeId identificador del origen (debe matchear X-Node-Id firmante NO; ver handler
 *     el firmante es el custodian, el {@code requesterNodeId} es a quién apuntan los fragments)
 * @param fragmentIds fragments custodiados por el custodian para ese requester
 */
public record KeepListRequestPayload(String requesterNodeId, List<String> fragmentIds) {

  public KeepListRequestPayload {
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      throw new IllegalArgumentException("requesterNodeId must not be blank");
    }
    if (fragmentIds == null) {
      fragmentIds = List.of();
    } else {
      fragmentIds = List.copyOf(fragmentIds);
    }
    requesterNodeId = requesterNodeId.trim();
  }
}
