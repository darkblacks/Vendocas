-- =====================================================================
-- V7: outbox, inbox dedup, jobs, integrations, audit, privacy, policies, support
-- =====================================================================
CREATE TABLE outbox_events (
    id                 uuid PRIMARY KEY,
    organization_id    uuid,
    aggregate_type     varchar(40) NOT NULL,
    aggregate_id       uuid        NOT NULL,
    event_type         varchar(60) NOT NULL,
    payload            jsonb       NOT NULL,
    available_at       timestamptz NOT NULL DEFAULT now(),
    published_at       timestamptz,
    attempts           int         NOT NULL DEFAULT 0,
    last_error         varchar(1000),
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_outbox_pending ON outbox_events(available_at) WHERE published_at IS NULL;

CREATE TABLE inbox_deduplication (
    consumer           varchar(80)  NOT NULL,
    event_id           varchar(200) NOT NULL,
    processed_at       timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- Durable generic jobs (sweeps, campaign runs, fiscal issuance, reconciliation)
CREATE TABLE scheduled_jobs (
    id                 uuid PRIMARY KEY,
    organization_id    uuid REFERENCES organizations(id) ON DELETE CASCADE,
    job_type           varchar(60)  NOT NULL,
    dedupe_key         varchar(250) NOT NULL UNIQUE,
    payload            jsonb        NOT NULL DEFAULT '{}'::jsonb,
    status             varchar(20)  NOT NULL CHECK (status IN ('PENDING','RUNNING','DONE','FAILED','DEAD')),
    run_at             timestamptz  NOT NULL,
    attempts           int          NOT NULL DEFAULT 0,
    max_attempts       int          NOT NULL DEFAULT 8,
    lease_until        timestamptz,
    leased_by          varchar(100),
    last_error         varchar(2000),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_jobs_due ON scheduled_jobs(run_at) WHERE status = 'PENDING';
CREATE INDEX ix_jobs_lease ON scheduled_jobs(lease_until) WHERE status = 'RUNNING';

-- Webhook receipts from third parties: signature verified, deduplicated by provider event id.
CREATE TABLE external_event_receipts (
    id                 uuid PRIMARY KEY,
    provider           varchar(30)  NOT NULL,
    provider_event_id  varchar(200) NOT NULL,
    event_type         varchar(100),
    organization_id    uuid,
    status             varchar(20)  NOT NULL CHECK (status IN ('RECEIVED','PROCESSED','IGNORED','FAILED')),
    payload_sha256     varchar(64)  NOT NULL,
    error              varchar(1000),
    received_at        timestamptz  NOT NULL DEFAULT now(),
    processed_at       timestamptz,
    UNIQUE (provider, provider_event_id)
);

CREATE TABLE integration_connections (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    kind               varchar(30) NOT NULL CHECK (kind IN ('API_KEY','INBOUND_WEBHOOK')),
    name               varchar(120) NOT NULL,
    key_prefix         varchar(16) NOT NULL UNIQUE,
    key_hash           varchar(128) NOT NULL,
    scopes             varchar(300) NOT NULL,
    status             varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    last_used_at       timestamptz,
    created_by         uuid REFERENCES users(id),
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE audit_logs (
    id                 uuid PRIMARY KEY,
    organization_id    uuid,
    actor_user_id      uuid,
    actor_type         varchar(20) NOT NULL CHECK (actor_type IN ('USER','PLATFORM_ADMIN','SYSTEM','PROVIDER','API_KEY')),
    action             varchar(80) NOT NULL,
    target_type        varchar(40),
    target_id          varchar(80),
    ip_address         varchar(64),
    details            jsonb       NOT NULL DEFAULT '{}'::jsonb,   -- never contains secrets
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_org ON audit_logs(organization_id, created_at DESC);
CREATE INDEX ix_audit_actor ON audit_logs(actor_user_id, created_at DESC);

CREATE TABLE privacy_requests (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    contact_id         uuid        REFERENCES contacts(id) ON DELETE SET NULL,
    type               varchar(20) NOT NULL CHECK (type IN ('ACCESS','EXPORT','RECTIFICATION','ERASURE','RESTRICTION','OBJECTION')),
    status             varchar(20) NOT NULL CHECK (status IN ('RECEIVED','VERIFYING','IN_PROGRESS','COMPLETED','REJECTED')),
    requester_email    citext,
    due_at             timestamptz NOT NULL,
    result_object_key  varchar(500),
    notes              varchar(2000),
    handled_by         uuid REFERENCES users(id),
    completed_at       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE system_notifications (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        REFERENCES organizations(id) ON DELETE CASCADE,
    user_id            uuid        REFERENCES users(id) ON DELETE CASCADE,
    severity           varchar(10) NOT NULL CHECK (severity IN ('INFO','WARNING','CRITICAL')),
    code               varchar(60) NOT NULL,
    title              varchar(200) NOT NULL,
    body               varchar(2000),
    dedupe_key         varchar(200) UNIQUE,
    read_at            timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_org ON system_notifications(organization_id, read_at, created_at DESC);

-- Versioned communication policy matrix: country × channel × purpose → required legal bases.
-- Lookup falls back to country '*' only when explicitly defined; otherwise DENY.
CREATE TABLE compliance_policies (
    id                 uuid PRIMARY KEY,
    policy_version     int         NOT NULL,
    country_code       varchar(2)  NOT NULL,   -- ISO alpha-2 or '*'
    channel            varchar(20) NOT NULL,
    purpose            varchar(30) NOT NULL,
    decision           varchar(20) NOT NULL CHECK (decision IN ('ALLOW_WITH_BASIS','BLOCK')),
    accepted_bases     varchar(200) NOT NULL DEFAULT '',  -- comma list of legal_basis values
    requires_unsubscribe boolean   NOT NULL DEFAULT true,
    quiet_hours_enforced boolean   NOT NULL DEFAULT true,
    notes              varchar(500),
    effective_from     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (policy_version, country_code, channel, purpose)
);

CREATE TABLE support_tickets (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    opened_by          uuid        NOT NULL REFERENCES users(id),
    subject            varchar(200) NOT NULL,
    body               text        NOT NULL,
    category           varchar(30) NOT NULL,
    status             varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','PENDING','RESOLVED','CLOSED')),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE abuse_flags (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    kind               varchar(40) NOT NULL,   -- HIGH_COMPLAINT_RATE, HIGH_BOUNCE_RATE, STOP_SPIKE, QUALITY_DROP
    metric_value       numeric(10,4),
    status             varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','REVIEWED','ACTIONED','DISMISSED')),
    created_at         timestamptz NOT NULL DEFAULT now()
);
