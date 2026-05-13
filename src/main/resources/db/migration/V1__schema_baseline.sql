-- =============================================================================
-- V1__schema_baseline.sql — Schema PostgreSQL del nodo
-- =============================================================================
-- Ver docs/architecture/database_overview.md para descripción
-- declarativa de las 23 tablas, sus relaciones y ownership por módulo.
-- =============================================================================

CREATE TABLE public.capacity_counter (
    id integer NOT NULL,
    occupied_bytes bigint NOT NULL
);

CREATE TABLE public.capacity_reservation (
    reservation_key character varying(128) NOT NULL,
    expected_storage_bytes bigint NOT NULL,
    status character varying(32) NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL
);

CREATE TABLE public.client_file_manifest (
    file_id character varying(64) NOT NULL,
    username character varying(128) NOT NULL,
    entry_id character varying(64) NOT NULL,
    original_size_bytes bigint NOT NULL,
    redundancy_n integer NOT NULL,
    redundancy_k integer NOT NULL,
    symbol_size integer NOT NULL,
    created_at timestamp without time zone NOT NULL,
    original_file_name character varying(512) NOT NULL,
    original_file_hash character varying(64) NOT NULL,
    directory_path character varying(1024) NOT NULL,
    compressed_size_bytes bigint,
    compression_algorithm character varying(64),
    fragment_count integer NOT NULL,
    fragment_size bigint NOT NULL,
    multi_block boolean DEFAULT false NOT NULL
);

CREATE TABLE public.client_file_manifest_block (
    file_id character varying(64) NOT NULL,
    block_index integer NOT NULL,
    block_size_bytes bigint NOT NULL,
    block_hash character varying(64) NOT NULL,
    fragment_hashes_json text NOT NULL
);

CREATE TABLE public.client_fragment_placement (
    file_id character varying(64) NOT NULL,
    fragment_id character varying(64) NOT NULL,
    fragment_index integer NOT NULL,
    parity boolean NOT NULL,
    custodian_node_id character varying(128) NOT NULL,
    custodian_base_url character varying(512) NOT NULL,
    agreement_id character varying(64) NOT NULL,
    fragment_checksum character varying(128) NOT NULL,
    fragment_size_bytes bigint NOT NULL,
    created_at timestamp without time zone NOT NULL,
    block_index integer DEFAULT 0 NOT NULL,
    health_status character varying(16) DEFAULT 'OK'::character varying NOT NULL,
    last_check_at timestamp without time zone,
    consecutive_failures integer DEFAULT 0 NOT NULL
);

CREATE TABLE public.custody_fragment (
    fragment_id character varying(128) NOT NULL,
    agreement_id character varying(128) NOT NULL,
    requester_node_id character varying(128) NOT NULL,
    checksum_algorithm character varying(64) NOT NULL,
    checksum character varying(512) NOT NULL,
    size_bytes integer NOT NULL,
    stored_at timestamp without time zone NOT NULL,
    expires_at timestamp without time zone NOT NULL
);

CREATE TABLE public.custody_fragment_payload (
    fragment_id character varying(128) NOT NULL,
    payload bytea NOT NULL
);

CREATE TABLE public.custody_probe_session (
    session_id character varying(160) NOT NULL,
    remote_node_id character varying(128) NOT NULL,
    direction character varying(32) NOT NULL,
    status character varying(32) NOT NULL,
    attempt_count integer NOT NULL,
    last_success_at timestamp without time zone,
    last_attempt_at timestamp without time zone,
    next_attempt_at timestamp without time zone,
    last_error character varying(1024),
    reverse_probe_cooldown_until timestamp without time zone,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    remote_tutor_base_url character varying(256)
);

CREATE TABLE public.discovery_candidate (
    node_id character varying(128) NOT NULL,
    failure_domain character varying(255) NOT NULL,
    original_requested_bucket bigint NOT NULL,
    accepted_buckets_json character varying(2000) NOT NULL,
    healthy boolean DEFAULT true NOT NULL,
    available_bytes bigint DEFAULT '9223372036854775807'::bigint NOT NULL,
    last_seen_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    base_url character varying(512) NOT NULL
);

CREATE TABLE public.discovery_retry_request (
    id character varying(128) NOT NULL,
    node_id character varying(128) NOT NULL,
    failure_domain character varying(255) NOT NULL,
    requested_bucket bigint NOT NULL,
    ratio double precision NOT NULL,
    max_candidates integer NOT NULL,
    target_failure_domain character varying(255),
    distribution_plan_json character varying(2000) NOT NULL,
    status character varying(32) NOT NULL,
    attempt_count integer NOT NULL,
    next_attempt_at timestamp without time zone NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    resolved_at timestamp without time zone,
    resolved_candidate_count integer,
    last_error character varying(512)
);

CREATE TABLE public.file_upload_session (
    session_id character varying(128) NOT NULL,
    username character varying(128) NOT NULL,
    entry_id character varying(128) NOT NULL,
    expected_size_bytes bigint NOT NULL,
    expected_checksum character varying(512) NOT NULL,
    uploaded_bytes bigint NOT NULL,
    status character varying(32) NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    completed_at timestamp without time zone
);

CREATE TABLE public.fs_entry (
    entry_id character varying(128) NOT NULL,
    username character varying(128) NOT NULL,
    path character varying(1024) NOT NULL,
    entry_type character varying(32) NOT NULL,
    size_bytes bigint NOT NULL,
    checksum character varying(512),
    version bigint NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    deleted boolean NOT NULL,
    content_uploaded boolean DEFAULT false NOT NULL,
    file_id character varying(64)
);

CREATE TABLE public.negotiation_agreement (
    agreement_id character varying(128) NOT NULL,
    requester_node_id character varying(128) NOT NULL,
    target_node_id character varying(128) NOT NULL,
    status character varying(32) NOT NULL,
    transfer_mode character varying(64) NOT NULL,
    bucket_size bigint NOT NULL,
    expected_storage_bytes bigint NOT NULL,
    fragment_count integer,
    redundancy_scheme character varying(128),
    planned_reservation_bytes bigint NOT NULL,
    created_at timestamp without time zone NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    requester_signature text NOT NULL,
    target_signature text,
    transfer_token character varying(256),
    transfer_token_issued_at timestamp without time zone,
    transfer_token_expires_at timestamp without time zone,
    requester_tutor_node_id character varying(128),
    requester_tutor_base_url character varying(256),
    file_id character varying(64)
);

CREATE TABLE public.origin_custodian_health (
    custodian_node_id character varying(128) NOT NULL,
    custodian_base_url character varying(512) NOT NULL,
    last_inbound_probe_at timestamp without time zone,
    consecutive_failures integer DEFAULT 0 NOT NULL,
    updated_at timestamp without time zone NOT NULL
);

CREATE TABLE public.recovery_admin_alerts (
    alert_id character varying(128) NOT NULL,
    file_id character varying(64) NOT NULL,
    level character varying(16) NOT NULL,
    event_code character varying(64) NOT NULL,
    payload_json text NOT NULL,
    raised_at timestamp without time zone NOT NULL,
    acknowledged_at timestamp without time zone
);

CREATE TABLE public.recovery_file_manifest (
    file_id character varying(64) NOT NULL,
    requester_node_id character varying(128) NOT NULL,
    requester_public_key text NOT NULL,
    directory_path character varying(1024) NOT NULL,
    original_file_name character varying(512) NOT NULL,
    original_file_hash character varying(64) NOT NULL,
    original_size_bytes bigint NOT NULL,
    compressed_size_bytes bigint,
    compression_algorithm character varying(64),
    redundancy_n integer NOT NULL,
    redundancy_k integer NOT NULL,
    stored_at timestamp without time zone NOT NULL,
    client_placements_json text,
    client_blocks_json text,
    last_supervised_check_at timestamp without time zone,
    consecutive_origin_failures integer DEFAULT 0 NOT NULL,
    multi_block boolean DEFAULT false NOT NULL
);

CREATE TABLE public.recovery_orphan_fragment (
    fragment_id character varying(128) NOT NULL,
    agreement_id character varying(128) NOT NULL,
    requester_node_id character varying(128) NOT NULL,
    checksum_algorithm character varying(64) NOT NULL,
    checksum character varying(512) NOT NULL,
    size_bytes integer NOT NULL,
    stored_at timestamp without time zone NOT NULL
);

CREATE TABLE public.recovery_orphan_fragment_payload (
    fragment_id character varying(128) NOT NULL,
    payload bytea NOT NULL
);

CREATE TABLE public.registration_code (
    code character varying(16) NOT NULL,
    quota_mb integer NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    used boolean NOT NULL,
    used_at timestamp without time zone,
    created_at timestamp without time zone NOT NULL,
    role character varying(32) DEFAULT 'END_USER'::character varying NOT NULL
);

CREATE TABLE public.used_nonce (
    nonce character varying(256) NOT NULL,
    expires_at timestamp without time zone NOT NULL
);

CREATE TABLE public.user_account (
    username character varying(128) NOT NULL,
    password_hash character varying(255) NOT NULL,
    quota_mb integer NOT NULL,
    created_at timestamp without time zone NOT NULL,
    role character varying(32) DEFAULT 'END_USER'::character varying NOT NULL,
    quota_used_bytes bigint DEFAULT 0 NOT NULL
);

CREATE TABLE public.user_session (
    token character varying(128) NOT NULL,
    username character varying(128) NOT NULL,
    issued_at timestamp without time zone NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    revoked boolean NOT NULL
);

ALTER TABLE public.capacity_counter
    ADD CONSTRAINT capacity_counter_pkey PRIMARY KEY (id);

ALTER TABLE public.capacity_reservation
    ADD CONSTRAINT capacity_reservation_pkey PRIMARY KEY (reservation_key);

ALTER TABLE public.client_file_manifest_block
    ADD CONSTRAINT client_file_manifest_block_pkey PRIMARY KEY (file_id, block_index);

ALTER TABLE public.client_file_manifest
    ADD CONSTRAINT client_file_manifest_pkey PRIMARY KEY (file_id);

ALTER TABLE public.client_fragment_placement
    ADD CONSTRAINT client_fragment_placement_pkey PRIMARY KEY (file_id, fragment_id);

ALTER TABLE public.custody_fragment_payload
    ADD CONSTRAINT custody_fragment_payload_pkey PRIMARY KEY (fragment_id);

ALTER TABLE public.custody_fragment
    ADD CONSTRAINT custody_fragment_pkey PRIMARY KEY (fragment_id);

ALTER TABLE public.custody_probe_session
    ADD CONSTRAINT custody_probe_session_pkey PRIMARY KEY (session_id);

ALTER TABLE public.discovery_candidate
    ADD CONSTRAINT discovery_candidate_pkey PRIMARY KEY (node_id);

ALTER TABLE public.discovery_retry_request
    ADD CONSTRAINT discovery_retry_request_pkey PRIMARY KEY (id);

ALTER TABLE public.file_upload_session
    ADD CONSTRAINT file_upload_session_pkey PRIMARY KEY (session_id);

ALTER TABLE public.fs_entry
    ADD CONSTRAINT fs_entry_pkey PRIMARY KEY (entry_id);

ALTER TABLE public.negotiation_agreement
    ADD CONSTRAINT negotiation_agreement_pkey PRIMARY KEY (agreement_id);

ALTER TABLE public.origin_custodian_health
    ADD CONSTRAINT origin_custodian_health_pkey PRIMARY KEY (custodian_node_id);

ALTER TABLE public.recovery_admin_alerts
    ADD CONSTRAINT recovery_admin_alerts_pkey PRIMARY KEY (alert_id);

ALTER TABLE public.recovery_file_manifest
    ADD CONSTRAINT recovery_file_manifest_pkey PRIMARY KEY (file_id);

ALTER TABLE public.recovery_orphan_fragment_payload
    ADD CONSTRAINT recovery_orphan_fragment_payload_pkey PRIMARY KEY (fragment_id);

ALTER TABLE public.recovery_orphan_fragment
    ADD CONSTRAINT recovery_orphan_fragment_pkey PRIMARY KEY (fragment_id);

ALTER TABLE public.registration_code
    ADD CONSTRAINT registration_code_pkey PRIMARY KEY (code);

ALTER TABLE public.used_nonce
    ADD CONSTRAINT used_nonce_pkey PRIMARY KEY (nonce);

ALTER TABLE public.user_account
    ADD CONSTRAINT user_account_pkey PRIMARY KEY (username);

ALTER TABLE public.user_session
    ADD CONSTRAINT user_session_pkey PRIMARY KEY (token);

CREATE INDEX idx_capacity_reservation_status ON public.capacity_reservation USING btree (status);

CREATE INDEX idx_capacity_reservation_updated_at ON public.capacity_reservation USING btree (updated_at);

CREATE INDEX idx_client_file_manifest_block_file_id ON public.client_file_manifest_block USING btree (file_id);

CREATE INDEX idx_client_file_manifest_entry_id ON public.client_file_manifest USING btree (entry_id);

CREATE INDEX idx_client_file_manifest_username ON public.client_file_manifest USING btree (username);

CREATE INDEX idx_client_fragment_placement_custodian_base_url ON public.client_fragment_placement USING btree (custodian_base_url);

CREATE INDEX idx_client_fragment_placement_custodian_health ON public.client_fragment_placement USING btree (custodian_node_id, health_status);

CREATE INDEX idx_client_fragment_placement_file_block ON public.client_fragment_placement USING btree (file_id, block_index, fragment_index);

CREATE INDEX idx_client_fragment_placement_file_id ON public.client_fragment_placement USING btree (file_id);

CREATE INDEX idx_client_fragment_placement_fragment_id ON public.client_fragment_placement USING btree (fragment_id);

CREATE INDEX idx_client_fragment_placement_health_status ON public.client_fragment_placement USING btree (health_status);

CREATE INDEX idx_custody_fragment_agreement_id ON public.custody_fragment USING btree (agreement_id);

CREATE INDEX idx_custody_fragment_expires_at ON public.custody_fragment USING btree (expires_at);

CREATE INDEX idx_custody_fragment_requester_node_id ON public.custody_fragment USING btree (requester_node_id);

CREATE INDEX idx_custody_probe_session_due ON public.custody_probe_session USING btree (direction, next_attempt_at);

CREATE INDEX idx_custody_probe_session_remote ON public.custody_probe_session USING btree (remote_node_id, updated_at DESC);

CREATE INDEX idx_discovery_candidate_active ON public.discovery_candidate USING btree (healthy, last_seen_at, available_bytes);

CREATE INDEX idx_discovery_candidate_failure_domain ON public.discovery_candidate USING btree (failure_domain);

CREATE INDEX idx_discovery_retry_request_node_id ON public.discovery_retry_request USING btree (node_id);

CREATE INDEX idx_discovery_retry_request_status_next_attempt ON public.discovery_retry_request USING btree (status, next_attempt_at);

CREATE INDEX idx_file_upload_session_status ON public.file_upload_session USING btree (status);

CREATE INDEX idx_file_upload_session_username ON public.file_upload_session USING btree (username);

CREATE INDEX idx_fs_entry_deleted ON public.fs_entry USING btree (deleted);

CREATE INDEX idx_fs_entry_file_id ON public.fs_entry USING btree (file_id);

CREATE INDEX idx_fs_entry_username_updated_at ON public.fs_entry USING btree (username, updated_at);

CREATE INDEX idx_negotiation_agreement_expires_at ON public.negotiation_agreement USING btree (expires_at);

CREATE INDEX idx_negotiation_agreement_file_id ON public.negotiation_agreement USING btree (file_id);

CREATE INDEX idx_negotiation_agreement_status ON public.negotiation_agreement USING btree (status);

CREATE INDEX idx_origin_custodian_health_last_probe ON public.origin_custodian_health USING btree (last_inbound_probe_at);

CREATE INDEX idx_recovery_admin_alerts_file_id ON public.recovery_admin_alerts USING btree (file_id);

CREATE INDEX idx_recovery_admin_alerts_level_raised ON public.recovery_admin_alerts USING btree (level, raised_at);

CREATE INDEX idx_recovery_file_manifest_node_silence ON public.recovery_file_manifest USING btree (requester_node_id, last_supervised_check_at);

CREATE INDEX idx_recovery_orphan_fragment_agreement_id ON public.recovery_orphan_fragment USING btree (agreement_id);

CREATE INDEX idx_recovery_orphan_fragment_requester_node_id ON public.recovery_orphan_fragment USING btree (requester_node_id);

CREATE INDEX idx_registration_code_expires_at ON public.registration_code USING btree (expires_at);

CREATE INDEX idx_registration_code_role ON public.registration_code USING btree (role);

CREATE INDEX idx_registration_code_used ON public.registration_code USING btree (used);

CREATE INDEX idx_used_nonce_expires_at ON public.used_nonce USING btree (expires_at);

CREATE INDEX idx_user_account_created_at ON public.user_account USING btree (created_at);

CREATE INDEX idx_user_account_role ON public.user_account USING btree (role);

CREATE INDEX idx_user_session_expires_at ON public.user_session USING btree (expires_at);

CREATE INDEX idx_user_session_revoked ON public.user_session USING btree (revoked);

CREATE INDEX idx_user_session_username ON public.user_session USING btree (username);

CREATE UNIQUE INDEX uk_fs_entry_username_path ON public.fs_entry USING btree (username, path);

ALTER TABLE public.client_file_manifest_block
    ADD CONSTRAINT fk_client_file_manifest_block_file_id FOREIGN KEY (file_id) REFERENCES public.client_file_manifest(file_id) ON DELETE CASCADE;

ALTER TABLE public.client_fragment_placement
    ADD CONSTRAINT fk_client_fragment_placement_file_id FOREIGN KEY (file_id) REFERENCES public.client_file_manifest(file_id) ON DELETE CASCADE;

ALTER TABLE public.custody_fragment_payload
    ADD CONSTRAINT fk_custody_fragment_payload_fragment FOREIGN KEY (fragment_id) REFERENCES public.custody_fragment(fragment_id) ON DELETE CASCADE;

ALTER TABLE public.recovery_admin_alerts
    ADD CONSTRAINT fk_recovery_admin_alerts_file_id FOREIGN KEY (file_id) REFERENCES public.recovery_file_manifest(file_id) ON DELETE SET NULL;

ALTER TABLE public.recovery_orphan_fragment_payload
    ADD CONSTRAINT fk_recovery_orphan_fragment_payload_fragment FOREIGN KEY (fragment_id) REFERENCES public.recovery_orphan_fragment(fragment_id) ON DELETE CASCADE;
