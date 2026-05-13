package es.ual.node.discovery.ports.out;

import es.ual.node.discovery.application.DiscoveryUnreachableException;
import es.ual.node.discovery.domain.DiscoveryRequest;
import es.ual.node.discovery.domain.DiscoveryResponse;

/**
 * Outbound port usado por nodos comunes para consultar candidatos a un supernodo discovery remoto
 * via signed HTTP.
 *
 * <p>Sólo los supernodos discovery sirven el endpoint {@code POST /ops/discovery/query}; los nodos
 * comunes usan este port para hablar con ellos.
 */
public interface RemoteDiscoveryQueryClientPort {

  /**
   * Queries one supernode discovery for candidates matching the request.
   *
   * @param supernodeBaseUrl base URL of the supernode (e.g. {@code http://node2:8080}). No puede
   *     ser blank.
   * @param request discovery request signed implicitly via the adapter's identity context
   * @return discovery response with candidates resolved by the supernode
   * @throws DiscoveryUnreachableException when the supernode is unreachable (timeout, IO error,
   *     5xx). Caller debe tratarlo como señal de failover (probar el siguiente supernodo).
   * @throws IllegalArgumentException when arguments son blank o invalid
   * @throws IllegalStateException for non-recoverable errors (4xx — bug en el caller)
   */
  DiscoveryResponse discover(String supernodeBaseUrl, DiscoveryRequest request);
}
