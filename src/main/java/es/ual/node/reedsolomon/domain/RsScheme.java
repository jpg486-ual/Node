package es.ual.node.reedsolomon.domain;

/** Reed-Solomon scheme parameters for an encoding session. */
public record RsScheme(int n, int k, int symbolSize) {

  /** Creates a validated Reed-Solomon scheme. */
  public RsScheme {
    if (n <= 0) {
      throw new IllegalArgumentException("n must be greater than zero");
    }
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than zero");
    }
    if (n < k) {
      throw new IllegalArgumentException("n must be greater than or equal to k");
    }
    if (symbolSize <= 0) {
      throw new IllegalArgumentException("symbolSize must be greater than zero");
    }
  }

  /**
   * Returns parity fragment count (n-k).
   *
   * @return parity fragment count
   */
  public int parityFragmentCount() {
    return n - k;
  }
}
