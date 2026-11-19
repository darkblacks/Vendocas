-- =====================================================================
-- V4: channels, provider accounts, credentials, templates, messages, conversations
-- =====================================================================
CREATE TABLE channel_connections (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    channel           varchar(20) NOT NULL CHECK (channel IN ('EMAIL','WHATSAPP','SMS','VOICE')),
    provider          varchar(30) NOT NULL,   -- SES, META_CLOUD, TWILIO, BSP_*
    status            varchar(30) NOT NULL CHECK (status IN ('NOT_CONFIGURED','PENDING_AUTHORIZATION','PENDING_VERIFICATION','PENDING_APPROVAL','ACTIVE','DEGRADED','SUSPENDED','DISCONNECTED','ERROR')),
    status_reason     varchar(500),
    action_required   varchar(500),
    display_name      varchar(200),
    external_account_id varchar(120),          -- WABA id, Twilio subaccount SID, SES identity
    config            jsonb       NOT NULL DEFAULT '{}'::jsonb,  -- non-secret provider config
    last_verified_at  timestamptz,
    last_error_at     timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0,
    UNIQUE (organization_id, channel)
);

CREATE TABLE channel_credentials (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    connection_id      uuid        NOT NULL REFERENCES channel_connections(id) ON DELETE CASCADE,
    kind               varchar(30) NOT NULL,   -- ACCESS_TOKEN, API_KEY, AUTH_TOKEN
    secret_enc         text        NOT NULL,   -- AES-256-GCM, key from secret manager; never returned by API
    key_version        int         NOT NULL DEFAULT 1,
    expires_at         timestamptz,
    rotated_at         timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (connection_id, kind)
);

CREATE TABLE sender_identities (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    connection_id     uuid        NOT NULL REFERENCES channel_connections(id) ON DELETE CASCADE,
    kind              varchar(30) NOT NULL CHECK (kind IN ('EMAIL_DOMAIN','EMAIL_ADDRESS','PLATFORM_SUBDOMAIN','WHATSAPP_NUMBER','SMS_NUMBER','SMS_ALPHA','SMS_SHORTCODE','VOICE_NUMBER')),
    value             varchar(320) NOT NULL,
    display_name      varchar(200),
    status            varchar(30) NOT NULL CHECK (status IN ('PENDING_REGISTRATION','PENDING_VERIFICATION','APPROVED','REJECTED','ACTIVE','SUSPENDED')),
    verification_data jsonb       NOT NULL DEFAULT '{}'::jsonb,  -- DNS records to publish, A2P brand/campaign ids, etc.
    external_id       varchar(120),           -- phone_number_id, Twilio PN SID
    quality_rating    varchar(20),            -- WhatsApp quality signal
    messaging_limit   varchar(40),            -- WhatsApp tier as reported by Meta
    is_default        boolean     NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, kind, value)
);

CREATE TABLE phone_numbers (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    e164              varchar(20) NOT NULL,
    country_code      varchar(2)     NOT NULL,
    provider          varchar(30) NOT NULL,
    provider_sid      varchar(120),
    ownership         varchar(20) NOT NULL CHECK (ownership IN ('PURCHASED','PORTED','HOSTED','FORWARDED','EXTERNAL_PBX')),
    capabilities      varchar(60) NOT NULL DEFAULT '',  -- comma set: SMS,MMS,VOICE
    status            varchar(30) NOT NULL CHECK (status IN ('PENDING_VERIFICATION','VERIFIED','TEST_PENDING','MONITORING','FAILED','RELEASED')),
    forward_to_e164   varchar(20),
    verified_at       timestamptz,
    test_call_passed_at timestamptz,         -- monitoring enabled only after a real test call is observed
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, e164)
);
CREATE UNIQUE INDEX ux_phone_numbers_provider_sid ON phone_numbers(provider, provider_sid) WHERE provider_sid IS NOT NULL;

-- Country × channel availability matrix (platform-managed). Unknown = unavailable.
CREATE TABLE channel_capabilities (
    country_code     varchar(2)     NOT NULL,
    channel          varchar(20) NOT NULL,
    capability       varchar(30) NOT NULL,   -- SEND_TRANSACTIONAL, SEND_MARKETING, MMS, ALPHA_SENDER, INBOUND, MISSED_CALL
    available        boolean     NOT NULL,
    requires         varchar(300),           -- e.g. 'A2P 10DLC brand+campaign registration'
    notes            varchar(500),
    reviewed_at      date        NOT NULL,
    PRIMARY KEY (country_code, channel, capability)
);

CREATE TABLE message_templates (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    channel           varchar(20) NOT NULL CHECK (channel IN ('EMAIL','WHATSAPP','SMS')),
    name              varchar(120) NOT NULL,
    locale            varchar(35) NOT NULL,
    purpose           varchar(30) NOT NULL CHECK (purpose IN ('TRANSACTIONAL','SERVICE_REMINDER','CUSTOMER_CARE','MARKETING','REACTIVATION')),
    wa_category       varchar(20) CHECK (wa_category IN ('MARKETING','UTILITY','AUTHENTICATION')),
    subject           varchar(300),
    body              text        NOT NULL,
    html_body         text,
    media_object_key  varchar(500),
    buttons           jsonb       NOT NULL DEFAULT '[]'::jsonb,
    variables         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    status            varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','PAUSED','DISABLED')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, channel, name, locale)
);

CREATE TABLE template_approvals (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    template_id       uuid        NOT NULL REFERENCES message_templates(id) ON DELETE CASCADE,
    provider          varchar(30) NOT NULL,
    provider_template_id varchar(120),
    status            varchar(20) NOT NULL,
    rejection_reason  varchar(500),
    submitted_at      timestamptz NOT NULL DEFAULT now(),
    decided_at        timestamptz
);
CREATE INDEX ix_template_approvals_tpl ON template_approvals(template_id, submitted_at DESC);

CREATE TABLE conversations (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    contact_id        uuid        NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    channel           varchar(20) NOT NULL,
    status            varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','PENDING','CLOSED')),
    assigned_to       uuid REFERENCES users(id),
    last_inbound_at   timestamptz,           -- drives WhatsApp 24h customer-service window
    last_message_at   timestamptz,
    unread_count      int         NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, contact_id, channel)
);
CREATE INDEX ix_conversations_inbox ON conversations(organization_id, status, last_message_at DESC);

CREATE TABLE messages (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    contact_id         uuid        REFERENCES contacts(id) ON DELETE SET NULL,
    conversation_id    uuid        REFERENCES conversations(id) ON DELETE SET NULL,
    direction          varchar(10) NOT NULL CHECK (direction IN ('OUTBOUND','INBOUND')),
    channel            varchar(20) NOT NULL CHECK (channel IN ('EMAIL','WHATSAPP','SMS','MMS')),
    purpose            varchar(30) NOT NULL,
    status             varchar(20) NOT NULL CHECK (status IN ('CREATED','QUEUED','SUBMITTED','SENT','DELIVERED','READ','REPLIED','FAILED','BLOCKED','CANCELED','RECEIVED')),
    status_reason      varchar(500),
    to_address         varchar(320),
    from_address       varchar(320),
    template_id        uuid REFERENCES message_templates(id),
    subject            varchar(300),
    body               text,
    media_object_key   varchar(500),
    provider           varchar(30),
    provider_message_id varchar(200),
    origin_type        varchar(30),          -- AUTOMATION, CAMPAIGN, MANUAL, TEST, SYSTEM
    origin_id          uuid,
    idempotency_key    varchar(200) NOT NULL,
    is_test            boolean     NOT NULL DEFAULT false,
    cost_reserved_minor bigint     NOT NULL DEFAULT 0,
    cost_charged_minor bigint      NOT NULL DEFAULT 0,
    cost_currency      varchar(3),
    queued_at          timestamptz,
    sent_at            timestamptz,
    delivered_at       timestamptz,
    read_at            timestamptz,
    failed_at          timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,
    UNIQUE (organization_id, idempotency_key)
);
CREATE UNIQUE INDEX ux_messages_provider_id ON messages(provider, provider_message_id) WHERE provider_message_id IS NOT NULL;
CREATE INDEX ix_messages_org_created ON messages(organization_id, created_at DESC);
CREATE INDEX ix_messages_contact ON messages(contact_id, created_at DESC);
CREATE INDEX ix_messages_queue ON messages(status, queued_at) WHERE status = 'QUEUED';
CREATE INDEX ix_messages_freq ON messages(organization_id, contact_id, purpose, created_at) WHERE direction = 'OUTBOUND';

CREATE TABLE message_attempts (
    id                uuid PRIMARY KEY,
    message_id        uuid        NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    attempt_no        int         NOT NULL,
    outcome           varchar(20) NOT NULL CHECK (outcome IN ('ACCEPTED','RETRYABLE_ERROR','PERMANENT_ERROR','TIMEOUT','CIRCUIT_OPEN')),
    error_code        varchar(80),
    error_message     varchar(1000),
    latency_ms        int,
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (message_id, attempt_no)
);

CREATE TABLE message_events (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    message_id        uuid        NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    type              varchar(20) NOT NULL,   -- SENT, DELIVERED, READ, FAILED, BOUNCED, COMPLAINED, CLICKED, REPLIED
    provider_event_id varchar(200),
    occurred_at       timestamptz NOT NULL,
    detail            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_message_events_provider ON message_events(message_id, type, provider_event_id) WHERE provider_event_id IS NOT NULL;
CREATE INDEX ix_message_events_msg ON message_events(message_id, occurred_at);
