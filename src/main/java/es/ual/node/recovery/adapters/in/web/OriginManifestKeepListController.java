package es.ual.node.recovery.adapters.in.web;

import es.ual.node.filesystem.ports.out.FileManifestPort;
import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Origen-side handler for the tutor's keep-list probe. The tutor periodically pings this endpoint
 * and the origin responds with the complete whitelist of file ids it currently has locally; the
 * tutor then purges any custodied manifest not in the list.
 *
 * <p>Auth: signed inter-node request validated via {@link RequestSignatureValidator} interceptor
 * (X-Node-Id / X-Signature). The response carries the local origin's manifest list sin filtering
 * por nodeId since this endpoint serves the local node's own manifests, not other peers'.
 *
 * <p>Sin {@code @ConditionalOnProperty(recovery-enabled)}: el endpoint vive en el ORIGEN, no en el
 * tutor. En cluster multi-nodo el origen normalmente no tiene {@code recovery-enabled=true} (esa
 * flag identifica al tutor). El endpoint solo lee {@link FileManifestPort#findAllFileIds()},
 * disponible en todos los nodos y devuelve lista vacía si no hay manifests locales.
 */
@RestController
@RequestMapping("/ops/tutor")
public class OriginManifestKeepListController {

  private final FileManifestPort localManifestPort;

  /** Creates controller. */
  public OriginManifestKeepListController(final FileManifestPort localManifestPort) {
    if (localManifestPort == null) {
      throw new IllegalArgumentException("localManifestPort must not be null");
    }
    this.localManifestPort = localManifestPort;
  }

  /**
   * Returns the whitelist of file ids the origin currently holds. The tutor uses this to purge
   * manifests not mentioned.
   */
  @GetMapping("/manifest-keep-list")
  public ResponseEntity<OriginManifestKeepListResponsePayload> keepList(
      final HttpServletRequest request) {
    final String nodeId = request.getHeader(RequestSignatureValidator.HEADER_NODE_ID);
    if (nodeId == null || nodeId.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(
        new OriginManifestKeepListResponsePayload(localManifestPort.findAllFileIds()));
  }
}
