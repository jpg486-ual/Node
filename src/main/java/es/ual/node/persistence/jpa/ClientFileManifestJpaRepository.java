package es.ual.node.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** JPA repository for client-side file manifests. */
public interface ClientFileManifestJpaRepository
    extends JpaRepository<ClientFileManifestJpaEntity, String> {}
