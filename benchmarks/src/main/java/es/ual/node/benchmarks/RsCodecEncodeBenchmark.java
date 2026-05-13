package es.ual.node.benchmarks;

import es.ual.node.reedsolomon.adapters.out.memory.InMemoryRsCodecAdapter;
import es.ual.node.reedsolomon.domain.RsScheme;
import java.security.SecureRandom;
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
 * Microbenchmark for the encode hot path in {@link InMemoryRsCodecAdapter}. Reed-Solomon erasure
 * coding is CPU-intensive and lives in the critical path of every fragment ingest.
 *
 * <p>Parameters: input size (1KB / 10KB / 100KB) × scheme (k=3, n=5, symbolSize=512).
 *
 * <p>Mode: AverageTime (ns/op) + Throughput (ops/s) — both reported.
 *
 * <p>Reference: Aleksey Shipilev, JMH (OpenJDK) — "Java Microbenchmark Harness". Brendan Gregg,
 * Systems Performance (2nd ed., 2020) ch. 12 — methodology benchmarks.
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class RsCodecEncodeBenchmark {

  private static final int SYMBOL_SIZE = 512;
  private static final int K = 3;
  private static final int N = 5;

  @Param({"1024", "10240", "102400"})
  public int inputSize;

  private InMemoryRsCodecAdapter codec;
  private RsScheme scheme;
  private byte[] input;

  @Setup
  public void setup() {
    this.codec = new InMemoryRsCodecAdapter();
    this.scheme = new RsScheme(N, K, SYMBOL_SIZE);
    this.input = new byte[inputSize];
    new SecureRandom().nextBytes(this.input);
  }

  @Benchmark
  public void encode(final Blackhole blackhole) {
    blackhole.consume(codec.encode(input, scheme));
  }
}
