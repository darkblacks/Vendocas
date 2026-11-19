-- =====================================================================
-- V3: contacts, identifiers, tags, custom fields, events, consent, suppression
-- =====================================================================
CREATE TABLE contacts (
    id                 uuid PRIMARY KEY,
    organization_id    uuid         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    external_id        varchar(120),
    name               varchar(250),
    email              citext,
    phone_e164         varchar(20),
    locale             varchar(35),
    country_code       varchar(2),
    time_zone          varchar(64),
    birth_date         date,
    last_interaction_at timestamptz,
    last_purchase_at   timestamptz,
    last_visit_at      timestamptz,
    next_appointment_at timestamptz,
    lifetime_value_minor bigint,
    lifetime_value_currency varchar(3),
    status             varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','BLOCKED','ARCHIVED','ERASED')),
    source             varchar(30)  NOT NULL,   -- MANUAL, IMPORT, API, FORM, SDK, INTEGRATION, INBOUND
    source_ref         varchar(120),            -- e.g. import id
    custom_fields      jsonb        NOT NULL DEFAULT '{}'::jsonb,  -- values for org-defined fields (definitions are relational)
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    version            bigint       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_contacts_external ON contacts(organization_id, external_id) WHERE external_id IS NOT NULL;
CREATE UNIQUE INDEX ux_contacts_email ON contacts(organization_id, email) WHERE email IS NOT NULL AND status <> 'ERASED';
CREATE UNIQUE INDEX ux_contacts_phone ON contacts(organization_id, phone_e164) WHERE phone_e164 IS NOT NULL AND status <> 'ERASED';
CREATE INDEX ix_contacts_org_created ON contacts(organization_id, created_at DESC);
CREATE INDEX ix_contacts_last_purchase ON contacts(organization_id, last_purchase_at);
CREATE INDEX ix_contacts_last_interaction ON contacts(organization_id, last_interaction_at);
CREATE INDEX ix_contacts_name_trgm ON contacts(organization_id, lower(name));

CREATE TABLE contact_identifiers (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    contact_id       uuid        NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    kind             varchar(20) NOT NULL CHECK (kind IN ('WHATSAPP_WA_ID','SDK_ANON_ID','EXTERNAL_SYSTEM','EMAIL_ALIAS','PHONE_ALIAS')),
    value            varchar(200) NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, kind, value)
);

CREATE TABLE tags (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name             varchar(80) NOT NULL,
    color            varchar(9),
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name)
);

CREATE TABLE contact_tags (
    contact_id  uuid NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    tag_id      uuid NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (contact_id, tag_id)
);
CREATE INDEX ix_contact_tags_tag ON contact_tags(tag_id);

CREATE TABLE contact_custom_fields (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    key              varchar(60) NOT NULL CHECK (key ~ '^[a-z][a-z0-9_]{0,59}$'),
    label            varchar(120) NOT NULL,
    data_type        varchar(20) NOT NULL CHECK (data_type IN ('TEXT','NUMBER','DATE','DATETIME','BOOLEAN')),
    is_sensitive     boolean     NOT NULL DEFAULT false,   -- e.g. health data: blocked from marketing personalization
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, key)
);

CREATE TABLE contact_events (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    contact_id       uuid        REFERENCES contacts(id) ON DELETE CASCADE,
    type             varchar(40) NOT NULL,
    occurred_at      timestamptz NOT NULL,
    source           varchar(30) NOT NULL,
    idempotency_key  varchar(200),
    properties       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_contact_events_idem ON contact_events(organization_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_contact_events_contact ON contact_events(contact_id, occurred_at DESC);
CREATE INDEX ix_contact_events_org_type ON contact_events(organization_id, type, occurred_at DESC);

-- Consent is per contact × channel × purpose. Absence of a row = no consent (deny by default).
CREATE TABLE contact_consents (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    contact_id       uuid        NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    channel          varchar(20) NOT NULL CHECK (channel IN ('EMAIL','WHATSAPP','SMS','VOICE')),
    purpose          varchar(30) NOT NULL CHECK (purpose IN ('TRANSACTIONAL','SERVICE_REMINDER','CUSTOMER_CARE','MARKETING','REACTIVATION')),
    status           varchar(20) NOT NULL CHECK (status IN ('GRANTED','WITHDRAWN','DENIED')),
    legal_basis      varchar(30) NOT NULL CHECK (legal_basis IN ('CONSENT','CONTRACT','LEGITIMATE_INTEREST','EXISTING_CUSTOMER_SOFT_OPT_IN','LEGAL_OBLIGATION')),
    granted_at       timestamptz,
    withdrawn_at     timestamptz,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (contact_id, channel, purpose)
);
CREATE INDEX ix_consents_org ON contact_consents(organization_id, channel, purpose, status);

CREATE TABLE consent_evidence (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    consent_id       uuid        NOT NULL REFERENCES contact_consents(id) ON DELETE CASCADE,
    action           varchar(20) NOT NULL CHECK (action IN ('GRANTED','WITHDRAWN','DENIED')),
    source           varchar(40) NOT NULL,   -- FORM, IMPORT_DECLARED, API, INBOUND_STOP, UNSUBSCRIBE_LINK, MANUAL, DOUBLE_OPT_IN
    captured_by      varchar(80),            -- user:<id> | system
    ip_address       varchar(64),
    user_agent       varchar(300),
    wording          text,                   -- consent text shown to the consumer
    form_url         varchar(1000),
    proof_object_key varchar(500),
    occurred_at      timestamptz NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_consent_evidence_consent ON consent_evidence(consent_id, occurred_at);

CREATE TABLE suppression_list (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    channel          varchar(20) NOT NULL CHECK (channel IN ('EMAIL','WHATSAPP','SMS','VOICE')),
    address          varchar(320) NOT NULL,   -- normalized email / E.164 phone
    reason           varchar(30) NOT NULL CHECK (reason IN ('UNSUBSCRIBED','HARD_BOUNCE','COMPLAINT','STOP_KEYWORD','MANUAL','PROVIDER_BLOCK','ERASURE')),
    scope            varchar(20) NOT NULL DEFAULT 'MARKETING' CHECK (scope IN ('MARKETING','ALL')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, channel, address)
);

CREATE TABLE contact_imports (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    file_name        varchar(255) NOT NULL,
    file_object_key  varchar(500),
    file_content     bytea,        -- raw upload kept only until file_expires_at (purged by retention job)
    format           varchar(10) NOT NULL CHECK (format IN ('CSV','XLSX')),
    status           varchar(20) NOT NULL CHECK (status IN ('UPLOADED','MAPPED','PREVIEWED','PROCESSING','COMPLETED','FAILED','ROLLED_BACK')),
    column_mapping   jsonb,
    declared_consent jsonb,     -- what the importer declared about consent (channel/purpose/basis/evidence)
    total_rows       int         NOT NULL DEFAULT 0,
    created_rows     int         NOT NULL DEFAULT 0,
    updated_rows     int         NOT NULL DEFAULT 0,
    skipped_rows     int         NOT NULL DEFAULT 0,
    error_rows       int         NOT NULL DEFAULT 0,
    errors           jsonb       NOT NULL DEFAULT '[]'::jsonb,
    created_by       uuid        NOT NULL REFERENCES users(id),
    file_expires_at  timestamptz,
    completed_at     timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);
