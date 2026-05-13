package es.ual.node.userregistration.adapters.out.memory;

import es.ual.node.userregistration.domain.RegistrationCode;
import es.ual.node.userregistration.ports.out.RegistrationCodePort;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory invitation code adapter. */
public class InMemoryRegistrationCodePort implements RegistrationCodePort {

  private final Map<String, RegistrationCode> codes = new ConcurrentHashMap<>();

  @Override
  public void save(final RegistrationCode registrationCode) {
    if (registrationCode == null) {
      throw new IllegalArgumentException("registrationCode must not be null");
    }
    codes.put(normalizeCode(registrationCode.code()), registrationCode);
  }

  @Override
  public Optional<RegistrationCode> findByCode(final String code) {
    return Optional.ofNullable(codes.get(normalizeCode(code)));
  }

  @Override
  public void markUsed(final String code, final Instant usedAt) {
    if (usedAt == null) {
      throw new IllegalArgumentException("usedAt must not be null");
    }
    final String normalized = normalizeCode(code);
    final RegistrationCode previous = codes.get(normalized);
    if (previous == null) {
      return;
    }
    codes.put(
        normalized,
        new RegistrationCode(
            previous.code(),
            previous.quotaMb(),
            previous.role(),
            previous.expiresAt(),
            true,
            usedAt,
            previous.createdAt()));
  }

  private String normalizeCode(final String code) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
    return code.trim().toUpperCase();
  }
}
