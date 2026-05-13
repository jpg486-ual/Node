package es.ual.node.recovery.ports.out;

import java.util.List;

/**
 * Outbound port (tutor-side) for invoking the origin's {@code GET /ops/tutor/manifest-keep-list}
 * endpoint. The tutor periodically asks each supervised node which manifests it wants to keep.
 * Manifests not mentioned in the response are purged.
 *
 * <p>The implementation MUST sign the canonical {@code (method, path, query, nonce, timestamp)}
 * tuple expected by {@code RequestSignatureValidator}.
 */
public interface RemoteOriginKeepListClientPort {

  /**
   * Issues {@code GET /ops/tutor/manifest-keep-list} against the origin's base URL. Returns the
   * list of file ids the origin currently has locally.
   *
   * @param originBaseUrl base URL of the origin node (no trailing slash)
   * @return whitelist of file ids (may be empty)
   * @throws IllegalStateException on transport, signature, or non-2xx HTTP response
   */
  List<String> fetchKeepList(String originBaseUrl);
}
