package es.ual.node.custodyliveness.adapters.in.web;

import java.util.List;

/**
 * Respuesta del origen al probe del custodian. El origen indica al custodian qué fragments debe
 * conservar: cualquier fragment listado en la request que NO aparezca en {@code keepFragmentIds}
 * debe ser purgado por el custodian (hard-delete + cancel agreement).
 *
 * @param keepFragmentIds whitelist de fragments a conservar
 */
public record KeepListResponsePayload(List<String> keepFragmentIds) {

  public KeepListResponsePayload {
    if (keepFragmentIds == null) {
      keepFragmentIds = List.of();
    } else {
      keepFragmentIds = List.copyOf(keepFragmentIds);
    }
  }
}
