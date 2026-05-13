package es.ual.node.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for recovery-domain orphan fragment payload bytes. */
public interface RecoveryOrphanFragmentPayloadJpaRepository
    extends JpaRepository<RecoveryOrphanFragmentPayloadJpaEntity, String> {}
