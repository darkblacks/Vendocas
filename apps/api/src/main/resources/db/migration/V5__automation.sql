-- =====================================================================
-- V5: automation engine
-- A definition has many immutable versions. The step graph of a version is stored as a
-- validated JSON document (immutable snapshot, never queried by field); everything the
-- engine queries on (trigger type, state, run time, idempotency) is relational.
-- =====================================================================
CREATE TABLE automation_definitions (
    id                 uuid PRIMARY KEY,
    organization_id    uuid         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name               varchar(150) NOT NULL,
    description        varchar(1000),
    status             varchar(20)  NOT NULL CHECK (status IN ('DRAFT','ACTIVE','PAUSED','ARCHIVED')),
    editor_mode        varchar(10)  NOT NULL DEFAULT 'SIMPLE' CHECK (editor_mode IN ('SIMPLE','ADVANCED')),
    draft_version_id   uuid,
    active_version_id  uuid,
    trigger_type       varchar(40),   -- denormalized from active version for fast matching
    created_by         uuid REFERENCES users(id),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    version            bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_automation_defs_match ON automation_definitions(organization_id, trigger_type) WHERE status = 'ACTIVE';

CREATE TABLE automation_versions (
    id                 uuid PRIMARY KEY,
    automation_id      uuid        NOT NULL REFERENCES automation_definitions(id) ON DELETE CASCADE,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    version_no         int         NOT NULL,
    trigger_type       varchar(40) NOT NULL,
    definition         jsonb       NOT NULL,          -- {trigger, steps[], settings}
    migration_policy   varchar(20) NOT NULL DEFAULT 'KEEP_ON_OLD' CHECK (migration_policy IN ('KEEP_ON_OLD','CANCEL_RUNNING')),
    published_at       timestamptz,                   -- NULL = editable draft; set = immutable
    created_by         uuid REFERENCES users(id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (automation_id, version_no)
);
ALTER TABLE automation_definitions
    ADD CONSTRAINT fk_auto_def_draft FOREIGN KEY (draft_version_id) REFERENCES automation_versions(id) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_auto_def_active FOREIGN KEY (active_version_id) REFERENCES automation_versions(id) DEFERRABLE INITIALLY DEFERRED;

-- Relational projection of each published version's trigger for matching/reporting.
CREATE TABLE automation_triggers (
    version_id        uuid PRIMARY KEY REFERENCES automation_versions(id) ON DELETE CASCADE,
    organization_id   uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    automation_id     uuid        NOT NULL REFERENCES automation_definitions(id) ON DELETE CASCADE,
    trigger_type      varchar(40) NOT NULL,
    event_type        varchar(60),          -- for EXTERNAL_EVENT / CONTACT_EVENT triggers
    period_amount     int,                  -- for INACTIVITY / NO_PURCHASE_WITHIN
    period_unit       varchar(20),
    recurrence        varchar(20),          -- DAILY / WEEKLY / MONTHLY
    local_time        time,
    days_of_week      smallint              -- bitmask Mon=1 .. Sun=64
);

CREATE TABLE automation_instances (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    automation_id      uuid        NOT NULL REFERENCES automation_definitions(id) ON DELETE CASCADE,
    version_id         uuid        NOT NULL REFERENCES automation_versions(id),
    contact_id         uuid        REFERENCES contacts(id) ON DELETE CASCADE,
    source_event_type  varchar(60) NOT NULL,
    source_event_id    uuid,
    idempotency_key    varchar(250) NOT NULL,   -- e.g. INACTIVITY:<contact>:<anchor-date>
    state              varchar(20) NOT NULL CHECK (state IN ('PENDING','WAITING','READY','PROCESSING','COMPLETED','SKIPPED','CANCELED','FAILED','DEAD_LETTER')),
    current_step_id    varchar(64),
    next_run_at        timestamptz,
    lease_until        timestamptz,             -- crash recovery: expired lease → reclaimable
    leased_by          varchar(100),
    attempts           int         NOT NULL DEFAULT 0,
    context            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    blocked_reason     varchar(300),
    result             varchar(300),
    started_at         timestamptz NOT NULL DEFAULT now(),
    completed_at       timestamptz,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,
    UNIQUE (automation_id, idempotency_key)
);
CREATE INDEX ix_auto_inst_due ON automation_instances(next_run_at) WHERE state IN ('PENDING','WAITING','READY');
CREATE INDEX ix_auto_inst_lease ON automation_instances(lease_until) WHERE state = 'PROCESSING';
CREATE INDEX ix_auto_inst_contact ON automation_instances(contact_id, state);
CREATE INDEX ix_auto_inst_automation ON automation_instances(automation_id, started_at DESC);

CREATE TABLE automation_executions (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    instance_id        uuid        NOT NULL REFERENCES automation_instances(id) ON DELETE CASCADE,
    step_id            varchar(64) NOT NULL,
    step_type          varchar(40) NOT NULL,
    outcome            varchar(20) NOT NULL CHECK (outcome IN ('PASSED','FAILED_CONDITION','WAITING','EXECUTED','SKIPPED','BLOCKED','ERROR','RETRY_SCHEDULED')),
    detail             varchar(1000),
    message_id         uuid REFERENCES messages(id) ON DELETE SET NULL,
    attempt_no         int         NOT NULL DEFAULT 1,
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_auto_exec_instance ON automation_executions(instance_id, created_at);
