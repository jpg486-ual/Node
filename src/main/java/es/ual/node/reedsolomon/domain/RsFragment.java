package es.ual.node.reedsolomon.domain;

import java.util.Arrays;

/** Single Reed-Solomon fragment with payload and checksum metadata. */
public record RsFragment(
    String fragmentId,
    int index,
    boolean isParity,
    String checksum,
    int payloadSize,
    byte[] payload) {

  /** Creates a validated immutable fragment. */
  public RsFragment {
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    if (index < 0) {
      throw new IllegalArgumentException("index must be greater than or equal to zero");
    }
    if (checksum == null || checksum.isBlank()) {
      throw new IllegalArgumentException("checksum must not be blank");
    }
    if (payloadSize <= 0) {
      throw new IllegalArgumentException("payloadSize must be greater than zero");
    }
    if (payload == null || payload.length == 0) {
      throw new IllegalArgumentException("payload must not be empty");
    }
    if (payload.length != payloadSize) {
      throw new IllegalArgumentException("payloadSize must match payload length");
    }

    fragmentId = fragmentId.trim();
    checksum = checksum.trim();
    payload = Arrays.copyOf(payload, payload.length);
  }

  @Override
  public byte[] payload() {
    return Arrays.copyOf(payload, payload.length);
  }
}
