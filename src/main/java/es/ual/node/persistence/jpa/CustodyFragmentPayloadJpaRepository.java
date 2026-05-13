package es.ual.node.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for custody-domain fragment payload bytes. */
public interface CustodyFragmentPayloadJpaRepository
    extends JpaRepository<CustodyFragmentPayloadJpaEntity, String> {}
