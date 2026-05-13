package es.ual.node.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** JPA repository for registration invitation codes. */
public interface RegistrationCodeJpaRepository
    extends JpaRepository<RegistrationCodeJpaEntity, String> {}
