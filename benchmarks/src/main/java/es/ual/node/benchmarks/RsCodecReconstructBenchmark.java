package es.ual.node.benchmarks;

import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsCodecAdapter;
import es.ual.node.reedsolomon.domain.RsFragment;
import es.ual.node.reedsolomon.domain.RsScheme;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Microbenchmark for the reconstruct (decode) hot path in {@link InMemoryRsCodecAdapter}. Reed-
 * Solomon decoding involves matrix inversion + parity recomputation; this is the critical path of
 * the recovery flow when fragments are missing.
 *
 * <p>Parameters: input size × missing fragments count (0 = fast path, 1, 2 = parity invocation).
 *
 * <p>Mode: AverageTime (us/op).
 *
 * <p>Reference: Aleksey Shipilev, JMH (OpenJDK). Brendan Gregg, Systems Performance ch. 12.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class RsCodecReconstructBenchmark {

  private static final int SYMBOL_SIZE = 512;
  private static final int K = 3;
  private static final int N = 5;

  @Param({"1024", "10240", "102400"})
  public int inputSize;

  @Param({"0", "1", "2"})
  public int missingCount;

  private InMemoryRsCodecAdapter codec;
  private RsScheme scheme;
  private List<RsFragment> availableFragments;

  @Setup
  public void setup() {
    this.codec = new InMemoryRsCodecAdapter();
    this.scheme = new RsScheme(N, K, SYMBOL_SIZE);
    final byte[] input = new byte[inputSize];
    new SecureRandom().nextBytes(input);
    final List<RsFragment> allFragments = codec.encode(input, scheme);
    final List<RsFragment> selected = new ArrayList<>(allFragments);
    for (int removed = 0; removed < missingCount; removed++) {
      selected.removeFirst();
    }
    this.availableFragments = List.copyOf(selected);
  }

  @Benchmark
  public void reconstruct(final Blackhole blackhole) {
    blackhole.consume(codec.reconstruct(availableFragments, scheme));
  }
}
