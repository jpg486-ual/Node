package es.ual.node.reedsolomon.ports.out;

import es.ual.node.reedsolomon.domain.RsFragment;
import es.ual.node.reedsolomon.domain.RsScheme;
import java.util.List;

/** Outbound port for Reed-Solomon encoding into data/parity fragments. */
public interface RsEncoderPort {

  /**
   * Encodes input bytes using the provided RS scheme.
   *
   * @param input source bytes
   * @param scheme Reed-Solomon scheme
   * @return generated fragments
   */
  List<RsFragment> encode(byte[] input, RsScheme scheme);
}
