package es.ual.node.benchmarks;

import es.ual.node.identitysecurity.application.RequestSignatureValidationService;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
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
 * Microbenchmarks for the inter-node signed request hot path.
 * Two benchmarks measure the two main cost components:
 *
 * <ol>
 *   <li>{@link #buildCanonicalPayload} — string concatenation + normalization (pure CPU, no
 *       crypto).
 *   <li>{@link #ecdsaVerify} — secp256r1 verify of a pre-signed payload (java.security primitives,
 *       no Spring).
 * </ol>
 *
 * Together they bracket the cost of every inter-node signed request validation.
 *
 * <p>Parameters: body size (256B / 2KB / 16KB) for the canonical payload; the signature is over the
 * canonical payload bytes.
 *
 * <p>Mode: Throughput (ops/s).
 *
 * <p>Reference: Aleksey Shipilev, JMH (OpenJDK); RFC 9562 §6.4 (UUID v4 nonces); secp256r1 (NIST
 * P-256, SEC 2 §2.4.2).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class SignatureValidationBenchmark {

  @Param({"256", "2048", "16384"})
  public int bodySize;

  private String method;
  private String path;
  private String queryString;
  private String nonce;
  private long timestampEpochSeconds;

  private PublicKey publicKey;
  private byte[] signedPayloadBytes;
  private byte[] signatureBytes;

  @Setup
  public void setup() throws Exception {
    this.method = "POST";
    this.path = "/ops/custody-liveness/remote/node-2/probe-now";
    this.queryString = "";
    this.nonce = "b6f3c8aa-4d21-4f1c-8f77-0a9e66c7e101";
    this.timestampEpochSeconds = 1_730_000_000L;

    final byte[] body = new byte[bodySize];
    new SecureRandom().nextBytes(body);
    final String canonical =
        RequestSignatureValidationService.buildCanonicalPayload(
                method, path, queryString, nonce, timestampEpochSeconds)
            + "\n"
            + Base64.getEncoder().encodeToString(body);
    this.signedPayloadBytes = canonical.getBytes();

    final KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
    gen.initialize(new ECGenParameterSpec("secp256r1"));
    final KeyPair keyPair = gen.generateKeyPair();
    this.publicKey = keyPair.getPublic();

    final Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(keyPair.getPrivate());
    signer.update(signedPayloadBytes);
    this.signatureBytes = signer.sign();
  }

  @Benchmark
  public void buildCanonicalPayload(final Blackhole blackhole) {
    blackhole.consume(
        RequestSignatureValidationService.buildCanonicalPayload(
            method, path, queryString, nonce, timestampEpochSeconds));
  }

  @Benchmark
  public void ecdsaVerify(final Blackhole blackhole) throws Exception {
    final Signature verifier = Signature.getInstance("SHA256withECDSA");
    verifier.initVerify(publicKey);
    verifier.update(signedPayloadBytes);
    blackhole.consume(verifier.verify(signatureBytes));
  }
}
