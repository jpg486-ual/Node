package es.ual.node.custodyliveness.ports.out;

import java.util.List;

/**
 * Outbound port del custodian al origen para enviar el probe periódico de keep-list. El custodian
 * envía la lista de fragmentos custodiados para el {@code requesterNodeId} y recibe la whitelist a
 * conservar.
 */
public interface RemoteOriginKeepListClientPort {

  /**
   * Envía probe firmado al origen. Devuelve la whitelist con los fragments a conservar (cualquier
   * fragmento listado en {@code fragmentIds} que NO aparezca debe ser purgado por el custodian) y,
   * opcionalmente, el {@code tutorBaseUrl} que el origen anuncia de sí mismo.
   *
   * @param originBaseUrl base URL del origen (resuelto por el custodian via {@code
   *     node.custody-liveness.remote-base-urls})
   * @param requesterNodeId identificador del origen (debe matchear el suscriptor de los placements)
   * @param fragmentIds fragments custodiados por el custodian para ese origen
   * @return resultado con la whitelist y el tutor anunciado por el origen
   * @throws RemoteOriginKeepListException si la respuesta no es 200 o hay error de red
   */
  OriginKeepListResult requestKeepList(
      String originBaseUrl, String requesterNodeId, List<String> fragmentIds);

  /**
   * Resultado del keep-list: whitelist a conservar + tutor anunciado por el origen.
   *
   * @param keepFragmentIds whitelist devuelta por el origen (nunca {@code null})
   * @param tutorBaseUrl tutor base URL anunciado por el origen ({@code null} si no lo anuncia)
   */
  record OriginKeepListResult(List<String> keepFragmentIds, String tutorBaseUrl) {
    public OriginKeepListResult {
      keepFragmentIds = keepFragmentIds == null ? List.of() : List.copyOf(keepFragmentIds);
    }
  }

  /** Excepción de fallo de comunicación con el origen. */
  class RemoteOriginKeepListException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RemoteOriginKeepListException(final String message) {
      super(message);
    }

    public RemoteOriginKeepListException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }
}
