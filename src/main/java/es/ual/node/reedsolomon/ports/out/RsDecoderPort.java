package es.ual.node.reedsolomon.ports.out;

import es.ual.node.reedsolomon.domain.RsFragment;
import es.ual.node.reedsolomon.domain.RsScheme;
import java.util.List;

/** Outbound port for Reed-Solomon decode/reconstruction from fragment sets. */
public interface RsDecoderPort {

  /**
   * Reconstructs original bytes from available RS fragments.
   *
   * @param fragments available fragment subset
   * @param scheme Reed-Solomon scheme
   * @return reconstructed bytes
   */
  byte[] reconstruct(List<RsFragment> fragments, RsScheme scheme);
}
