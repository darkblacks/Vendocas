-- =====================================================================
-- V6: media, campaigns, telephony, commerce (orders/appointments/opportunities/conversions)
-- =====================================================================
CREATE TABLE media_assets (
    id                uuid PRIMARY KEY,
    organization_id   uuid         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    object_key        varchar(500) NOT NULL UNIQUE,
    original_name     varchar(255) NOT NULL,
    content_type      varchar(100) NOT NULL CHECK (content_type IN ('image/jpeg','image/png','image/webp','application/pdf')),
    size_bytes        bigint       NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    sha256            varchar(64)  NOT NULL,
    width             int,
    height            int,
    purpose           varchar(20)  NOT NULL CHECK (purpose IN ('LOGO','CAMPAIGN','ATTACHMENT','DOCUMENT')),
    created_by        uuid REFERENCES users(id),
    created_at        timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_media_org ON media_assets(organization_id, purpose, created_at DESC);

CREATE TABLE campaign_audiences (
    id                uuid PRIMARY KEY,
    organization_id   uuid         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name              varchar(150) NOT NULL,
    filter            jsonb        NOT NULL,   -- validated segment definition (tags, country, recency...)
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE campaigns (
    id                   uuid PRIMARY KEY,
    organization_id      uuid         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name                 varchar(150) NOT NULL,
    status               varchar(20)  NOT NULL CHECK (status IN ('DRAFT','SCHEDULED','ACTIVE','PAUSED','COMPLETED','CANCELED')),
    pause_reason         varchar(300),
    channel              varchar(20)  NOT NULL CHECK (channel IN ('EMAIL','WHATSAPP','SMS')),
    purpose              varchar(30)  NOT NULL DEFAULT 'MARKETING',
    template_id          uuid REFERENCES message_templates(id),
    subject              varchar(300),
    body                 text,
    media_asset_id       uuid REFERENCES media_assets(id),
    link_url             varchar(1000),
    audience_id          uuid REFERENCES campaign_audiences(id),
    max_recipients_per_run int        NOT NULL DEFAULT 500 CHECK (max_recipients_per_run BETWEEN 1 AND 50000),
    budget_per_run_minor bigint       NOT NULL CHECK (budget_per_run_minor >= 0),
    monthly_budget_minor bigint       NOT NULL CHECK (monthly_budget_minor >= 0),
    frequency_cap_days   int          NOT NULL DEFAULT 1 CHECK (frequency_cap_days >= 1),
    created_by           uuid REFERENCES users(id),
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    version              bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_campaigns_org ON campaigns(organization_id, status);

CREATE TABLE campaign_schedules (
    campaign_id       uuid PRIMARY KEY REFERENCES campaigns(id) ON DELETE CASCADE,
    organization_id   uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    kind              varchar(20) NOT NULL CHECK (kind IN ('ONCE','DAILY','WEEKLY','CUSTOM_DAYS')),
    days_of_week      smallint    NOT NULL DEFAULT 127,  -- bitmask Mon=1 .. Sun=64
    local_time        time        NOT NULL,
    time_zone         varchar(64) NOT NULL,
    start_date        date        NOT NULL,
    end_date          date,
    next_run_at       timestamptz,
    last_run_at       timestamptz
);
CREATE INDEX ix_campaign_sched_due ON campaign_schedules(next_run_at) WHERE next_run_at IS NOT NULL;

CREATE TABLE campaign_runs (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    campaign_id        uuid        NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    scheduled_for      timestamptz NOT NULL,
    status             varchar(20) NOT NULL CHECK (status IN ('PLANNED','RUNNING','COMPLETED','BLOCKED','FAILED','CANCELED')),
    blocked_reason     varchar(300),
    eligible_count     int         NOT NULL DEFAULT 0,
    queued_count       int         NOT NULL DEFAULT 0,
    skipped_count      int         NOT NULL DEFAULT 0,
    estimated_cost_minor bigint    NOT NULL DEFAULT 0,
    currency           varchar(3),
    started_at         timestamptz,
    completed_at       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (campaign_id, scheduled_for)
);

CREATE TABLE campaign_recipients (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    run_id             uuid        NOT NULL REFERENCES campaign_runs(id) ON DELETE CASCADE,
    contact_id         uuid        NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    message_id         uuid REFERENCES messages(id) ON DELETE SET NULL,
    status             varchar(20) NOT NULL CHECK (status IN ('QUEUED','SKIPPED')),
    skip_reason        varchar(100),
    created_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, contact_id)
);

CREATE TABLE tracked_links (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    message_id         uuid        NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    token              varchar(32) NOT NULL UNIQUE,
    target_url         varchar(2000) NOT NULL,
    first_clicked_at   timestamptz,
    click_count        int         NOT NULL DEFAULT 0   -- aggregate only; no IP/UA stored
);

-- Telephony ------------------------------------------------------------
CREATE TABLE call_events (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    phone_number_id    uuid        REFERENCES phone_numbers(id) ON DELETE SET NULL,
    contact_id         uuid        REFERENCES contacts(id) ON DELETE SET NULL,
    provider           varchar(30) NOT NULL,
    provider_call_id   varchar(120) NOT NULL,
    direction          varchar(10) NOT NULL CHECK (direction IN ('INBOUND','OUTBOUND')),
    from_e164          varchar(20),
    to_e164            varchar(20),
    status             varchar(20) NOT NULL,  -- RINGING, IN_PROGRESS, COMPLETED, BUSY, NO_ANSWER, FAILED, CANCELED
    answered           boolean,
    duration_seconds   int,
    started_at         timestamptz,
    ended_at           timestamptz,
    is_test            boolean     NOT NULL DEFAULT false,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_call_id)
);
CREATE INDEX ix_call_events_org ON call_events(organization_id, created_at DESC);

CREATE TABLE callback_tasks (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    contact_id         uuid        REFERENCES contacts(id) ON DELETE SET NULL,
    phone_e164         varchar(20),
    source_type        varchar(30) NOT NULL,   -- MISSED_CALL, AUTOMATION, MANUAL
    source_id          uuid,
    status             varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','DONE','CANCELED')),
    assigned_to        uuid REFERENCES users(id),
    due_at             timestamptz,
    outcome            varchar(30),            -- REACHED, NO_ANSWER, WRONG_NUMBER, SCHEDULED, SOLD
    notes              varchar(2000),
    completed_at       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_callback_open ON callback_tasks(organization_id, status, due_at);

CREATE TABLE missed_calls (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    call_event_id      uuid        NOT NULL UNIQUE REFERENCES call_events(id) ON DELETE CASCADE,
    contact_id         uuid        REFERENCES contacts(id) ON DELETE SET NULL,
    from_e164          varchar(20),
    occurred_at        timestamptz NOT NULL,
    callback_task_id   uuid REFERENCES callback_tasks(id) ON DELETE SET NULL,
    created_at         timestamptz NOT NULL DEFAULT now()
);

-- Commerce --------------------------------------------------------------
CREATE TABLE orders (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    contact_id         uuid        REFERENCES contacts(id) ON DELETE SET NULL,
    external_id        varchar(120) NOT NULL,
    status             varchar(20) NOT NULL CHECK (status IN ('PENDING','COMPLETED','CANCELED','REFUNDED')),
    amount_minor       bigint      NOT NULL CHECK (amount_minor >= 0),
    currency           varchar(3)     NOT NULL,
    source             varchar(30) NOT NULL CHECK (source IN ('SDK_SIGNED','INTEGRATION','API','MANUAL')),
    occurred_at        timestamptz NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, external_id)
);
CREATE INDEX ix_orders_contact ON orders(contact_id, occurred_at DESC);

CREATE TABLE appointments (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    contact_id         uuid        NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    external_id        varchar(120),
    starts_at          timestamptz NOT NULL,
    ends_at            timestamptz,
    status             varchar(20) NOT NULL CHECK (status IN ('SCHEDULED','CONFIRMED','CANCELED','COMPLETED','NO_SHOW')),
    title              varchar(200),
    location           varchar(300),
    source             varchar(30) NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_appointments_ext ON appointments(organization_id, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX ix_appointments_upcoming ON appointments(organization_id, starts_at) WHERE status IN ('SCHEDULED','CONFIRMED');

CREATE TABLE opportunities (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    contact_id         uuid        REFERENCES contacts(id) ON DELETE SET NULL,
    title              varchar(200) NOT NULL,
    status             varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CONTACTED','WON','LOST')),
    source_type        varchar(30) NOT NULL,
    source_id          uuid,
    value_minor        bigint,
    currency           varchar(3),
    closed_at          timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_opportunities_org ON opportunities(organization_id, status, created_at DESC);

-- Conversion attribution. Only orders with status COMPLETED and a trusted source are counted as confirmed revenue.
CREATE TABLE conversion_events (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    order_id           uuid        NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
    contact_id         uuid        REFERENCES contacts(id) ON DELETE SET NULL,
    message_id         uuid        REFERENCES messages(id) ON DELETE SET NULL,
    automation_id      uuid,
    campaign_id        uuid,
    attribution_model  varchar(30) NOT NULL DEFAULT 'LAST_TOUCH_7D',
    amount_minor       bigint      NOT NULL,
    currency           varchar(3)     NOT NULL,
    occurred_at        timestamptz NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_conversions_org ON conversion_events(organization_id, occurred_at DESC);
