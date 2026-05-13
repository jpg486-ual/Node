package es.ual.node.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** JPA repository for user sessions. */
public interface UserSessionJpaRepository extends JpaRepository<UserSessionJpaEntity, String> {}
