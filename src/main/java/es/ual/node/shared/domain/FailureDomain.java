package es.ual.node.shared.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable value object that represents a node failure domain tag. */
public final class FailureDomain {

  private static final Pattern ALLOWED = Pattern.compile("^[a-zA-Z0-9._/-]+$");

  private final String value;

  /**
   * Creates a validated failure domain instance.
   *
   * @param value failure domain value (for example, {@code zone-a/rack-1})
   * @return validated failure domain instance
   */
  public static FailureDomain of(final String value) {
    return new FailureDomain(value);
  }

  /**
   * Creates a new failure domain value object.
   *
   * @param value failure domain value
   */
  public FailureDomain(final String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Failure domain value must not be blank");
    }
    final String trimmed = value.trim();
    if (!ALLOWED.matcher(trimmed).matches()) {
      throw new IllegalArgumentException("Failure domain contains invalid characters");
    }

    this.value = trimmed;
  }

  /**
   * Checks if this domain matches a candidate domain prefix.
   *
   * @param candidate candidate domain prefix
   * @return {@code true} when this domain starts with the candidate value
   */
  public boolean matches(final FailureDomain candidate) {
    Objects.requireNonNull(candidate, "Candidate failure domain must not be null");
    return value.equals(candidate.value) || value.startsWith(candidate.value + "/");
  }

  /**
   * Compares this value object with another instance.
   *
   * @param o object to compare
   * @return {@code true} when both represent the same domain value
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FailureDomain that)) {
      return false;
    }
    return Objects.equals(value, that.value);
  }

  /**
   * Returns hash code derived from domain value.
   *
   * @return hash code
   */
  @Override
  public int hashCode() {
    return Objects.hash(value);
  }

  /**
   * Returns string representation of this failure domain (canonical normalized value).
   *
   * @return failure domain value
   */
  @Override
  public String toString() {
    return value;
  }
}
