package es.ual.node.reedsolomon.adapters.out.memory;

import com.backblaze.erasure.ReedSolomon;
import es.ual.node.reedsolomon.domain.RsFragment;
import es.ual.node.reedsolomon.domain.RsScheme;
import es.ual.node.reedsolomon.ports.out.RsDecoderPort;
import es.ual.node.reedsolomon.ports.out.RsEncoderPort;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** In-memory Reed-Solomon codec adapter backed by Backblaze JavaReedSolomon. */
public class InMemoryRsCodecAdapter implements RsEncoderPort, RsDecoderPort {

  @Override
  public List<RsFragment> encode(final byte[] input, final RsScheme scheme) {
    validateInput(input, scheme);

    final int shardSize = computeShardSize(input.length, scheme.k(), scheme.symbolSize());
    final byte[][] shards = createShardBuffer(scheme.n(), shardSize);

    final byte[] payloadWithLength = prependLengthHeader(input);
    fillDataShards(payloadWithLength, shards, scheme.k(), shardSize);

    final ReedSolomon codec = ReedSolomon.create(scheme.k(), scheme.parityFragmentCount());
    codec.encodeParity(shards, 0, shardSize);

    final List<RsFragment> fragments = new ArrayList<>(scheme.n());
    for (int index = 0; index < scheme.n(); index++) {
      final byte[] payload = Arrays.copyOf(shards[index], shardSize);
      fragments.add(
          new RsFragment(
              buildFragmentId(index),
              index,
              index >= scheme.k(),
              sha256Hex(payload),
              payload.length,
              payload));
    }
    return List.copyOf(fragments);
  }

  @Override
  public byte[] reconstruct(final List<RsFragment> fragments, final RsScheme scheme) {
    if (scheme == null) {
      throw new IllegalArgumentException("scheme must not be null");
    }
    if (fragments == null || fragments.isEmpty()) {
      throw new IllegalArgumentException("fragments must not be empty");
    }
    if (fragments.size() < scheme.k()) {
      throw new IllegalArgumentException("at least k fragments are required for reconstruction");
    }

    final int shardSize = resolveShardSize(fragments);
    final byte[][] shards = createShardBuffer(scheme.n(), shardSize);
    final boolean[] shardPresent = new boolean[scheme.n()];
    int presentCount = 0;

    for (RsFragment fragment : fragments) {
      if (fragment.index() < 0 || fragment.index() >= scheme.n()) {
        throw new IllegalArgumentException("fragment index out of range for scheme");
      }
      if (fragment.payloadSize() != shardSize) {
        throw new IllegalArgumentException("all fragments must have the same payloadSize");
      }
      if (!sha256Hex(fragment.payload()).equalsIgnoreCase(fragment.checksum())) {
        throw new IllegalArgumentException("fragment checksum does not match payload");
      }
      if (shardPresent[fragment.index()]) {
        throw new IllegalArgumentException("duplicate fragment index in reconstruction input");
      }

      shards[fragment.index()] = fragment.payload();
      shardPresent[fragment.index()] = true;
      presentCount++;
    }

    if (presentCount < scheme.k()) {
      throw new IllegalArgumentException("at least k fragments are required for reconstruction");
    }

    final ReedSolomon codec = ReedSolomon.create(scheme.k(), scheme.parityFragmentCount());
    codec.decodeMissing(shards, shardPresent, 0, shardSize);

    final byte[] flattenedDataShards = flattenDataShards(shards, scheme.k(), shardSize);
    final int originalLength = ByteBuffer.wrap(flattenedDataShards, 0, Integer.BYTES).getInt();
    if (originalLength < 0 || originalLength > flattenedDataShards.length - Integer.BYTES) {
      throw new IllegalArgumentException("reconstructed payload length header is invalid");
    }

    return Arrays.copyOfRange(flattenedDataShards, Integer.BYTES, Integer.BYTES + originalLength);
  }

  private static void validateInput(final byte[] input, final RsScheme scheme) {
    if (input == null || input.length == 0) {
      throw new IllegalArgumentException("input must not be empty");
    }
    if (scheme == null) {
      throw new IllegalArgumentException("scheme must not be null");
    }
  }

  private static int computeShardSize(
      final int inputLength, final int dataShards, final int symbolSize) {
    final int payloadWithLength = Integer.BYTES + inputLength;
    final int minimalShardSize = (payloadWithLength + dataShards - 1) / dataShards;
    return Math.max(symbolSize, minimalShardSize);
  }

  private static byte[][] createShardBuffer(final int totalShards, final int shardSize) {
    final byte[][] shards = new byte[totalShards][shardSize];
    for (int index = 0; index < totalShards; index++) {
      shards[index] = new byte[shardSize];
    }
    return shards;
  }

  private static byte[] prependLengthHeader(final byte[] input) {
    final ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + input.length);
    buffer.putInt(input.length);
    buffer.put(input);
    return buffer.array();
  }

  private static void fillDataShards(
      final byte[] payload, final byte[][] shards, final int dataShards, final int shardSize) {
    int offset = 0;
    for (int shardIndex = 0; shardIndex < dataShards; shardIndex++) {
      final int remaining = payload.length - offset;
      if (remaining <= 0) {
        break;
      }
      final int copiedBytes = Math.min(shardSize, remaining);
      System.arraycopy(payload, offset, shards[shardIndex], 0, copiedBytes);
      offset += copiedBytes;
    }
  }

  private static int resolveShardSize(final List<RsFragment> fragments) {
    final int shardSize = fragments.get(0).payloadSize();
    if (shardSize <= 0) {
      throw new IllegalArgumentException("payloadSize must be greater than zero");
    }
    return shardSize;
  }

  private static byte[] flattenDataShards(
      final byte[][] shards, final int dataShards, final int shardSize) {
    final byte[] flattened = new byte[dataShards * shardSize];
    for (int index = 0; index < dataShards; index++) {
      System.arraycopy(shards[index], 0, flattened, index * shardSize, shardSize);
    }
    return flattened;
  }

  private String buildFragmentId(final int index) {
    return "rs-fragment-" + index + "-" + UUID.randomUUID();
  }

  private String sha256Hex(final byte[] payload) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] bytes = digest.digest(payload);
      final StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte value : bytes) {
        builder.append(String.format("%02x", value));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 algorithm is not available", ex);
    }
  }
}
