-- =====================================================================
-- V1: identity, organizations, membership, tax profiles
-- Conventions: UUID PKs, timestamptz in UTC, money in minor units (bigint)
-- Every tenant-owned table carries organization_id NOT NULL + FK.
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id                  uuid PRIMARY KEY,
    email               citext      NOT NULL UNIQUE,
    full_name           varchar(200) NOT NULL,
    password_hash       varchar(255),
    email_verified_at   timestamptz,
    locale              varchar(35)  NOT NULL DEFAULT 'en',
    time_zone           varchar(64)  NOT NULL DEFAULT 'UTC',
    mfa_secret_enc      text,                    -- AES-GCM encrypted TOTP secret
    mfa_enabled_at      timestamptz,
    platform_role       varchar(30),             -- NULL | PLATFORM_ADMIN | PLATFORM_SUPPORT
    failed_logins       int          NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    last_login_at       timestamptz,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    version             bigint       NOT NULL DEFAULT 0
);

CREATE TABLE user_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose     varchar(30) NOT NULL CHECK (purpose IN ('EMAIL_VERIFICATION','PASSWORD_RESET','MFA_CHALLENGE')),
    token_hash  varchar(128) NOT NULL UNIQUE,    -- SHA-256 of the opaque token; raw token never stored
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_user_tokens_user ON user_tokens(user_id, purpose);

CREATE TABLE refresh_tokens (
    id           uuid PRIMARY KEY,
    user_id      uuid         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id    uuid         NOT NULL,          -- rotation family; reuse of a revoked token revokes the family
    token_hash   varchar(128) NOT NULL UNIQUE,
    expires_at   timestamptz  NOT NULL,
    revoked_at   timestamptz,
    replaced_by  uuid,
    mfa          boolean      NOT NULL DEFAULT false,  -- session established with a second factor
    user_agent   varchar(300),
    ip_address   varchar(64),
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens(family_id);

CREATE TABLE organizations (
    id                   uuid PRIMARY KEY,
    legal_name           varchar(250) NOT NULL,
    trade_name           varchar(250) NOT NULL,
    country_code         varchar(2)      NOT NULL,           -- ISO 3166-1 alpha-2 (country of incorporation)
    billing_country_code varchar(2)      NOT NULL,
    billing_currency     varchar(3)      NOT NULL CHECK (billing_currency IN ('USD','BRL','EUR')),
    locale               varchar(35)  NOT NULL,           -- BCP 47
    time_zone            varchar(64)  NOT NULL,           -- IANA
    data_region          varchar(20)  NOT NULL DEFAULT 'us' CHECK (data_region IN ('us','eu','br')),
    contact_email        citext       NOT NULL,
    finance_email        citext,
    contact_phone        varchar(20),                     -- E.164
    address_line1        varchar(250),
    address_line2        varchar(250),
    city                 varchar(120),
    region               varchar(120),
    postal_code          varchar(30),
    logo_object_key      varchar(500),
    status               varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED_ADMIN','CLOSED')),
    terms_accepted_at    timestamptz  NOT NULL,
    terms_version        varchar(20)  NOT NULL,
    public_key           varchar(64)  NOT NULL UNIQUE,    -- tracking SDK site key (not secret)
    tracking_secret_enc  text         NOT NULL,           -- HMAC secret for signed identify/purchase events
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    version              bigint       NOT NULL DEFAULT 0
);

CREATE TABLE organization_members (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id          uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role             varchar(20) NOT NULL CHECK (role IN ('OWNER','ADMIN','FINANCE','OPERATOR','READ_ONLY')),
    status           varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('INVITED','ACTIVE','DISABLED')),
    invited_by       uuid REFERENCES users(id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, user_id)
);
CREATE INDEX ix_org_members_user ON organization_members(user_id);

CREATE TABLE member_invitations (
    id               uuid PRIMARY KEY,
    organization_id  uuid         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email            citext       NOT NULL,
    role             varchar(20)  NOT NULL CHECK (role IN ('ADMIN','FINANCE','OPERATOR','READ_ONLY')),
    token_hash       varchar(128) NOT NULL UNIQUE,
    expires_at       timestamptz  NOT NULL,
    accepted_at      timestamptz,
    invited_by       uuid         NOT NULL REFERENCES users(id),
    created_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_invitation_open ON member_invitations(organization_id, email) WHERE accepted_at IS NULL;

-- Roles are a closed set enforced in code (com.platform.tenants.Role); this table documents
-- the role→permission matrix for auditing / admin display and is kept in sync by migration.
CREATE TABLE role_permissions (
    role        varchar(20)  NOT NULL,
    permission  varchar(60)  NOT NULL,
    PRIMARY KEY (role, permission)
);

CREATE TABLE organization_settings (
    organization_id         uuid PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    quiet_hours_start       time        NOT NULL DEFAULT '21:00',
    quiet_hours_end         time        NOT NULL DEFAULT '08:00',
    default_frequency_cap   int         NOT NULL DEFAULT 3,      -- max marketing messages / contact / 7 days
    daily_spend_limit_minor bigint      NOT NULL DEFAULT 2000,   -- in wallet currency minor units
    monthly_spend_limit_minor bigint    NOT NULL DEFAULT 20000,
    low_balance_threshold_minor bigint  NOT NULL DEFAULT 500,
    data_retention_days     int         NOT NULL DEFAULT 730,
    import_file_retention_days int      NOT NULL DEFAULT 7,
    require_mfa_for_admins  boolean     NOT NULL DEFAULT true,
    updated_at              timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tax_profiles (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    country_code       varchar(2)     NOT NULL,
    customer_type      varchar(10) NOT NULL DEFAULT 'BUSINESS' CHECK (customer_type IN ('BUSINESS','INDIVIDUAL')),
    tax_id_type        varchar(20) NOT NULL CHECK (tax_id_type IN ('BR_CNPJ','BR_CPF','US_EIN','EU_VAT','NONE','OTHER')),
    tax_id             varchar(40),
    tax_id_status      varchar(20) NOT NULL DEFAULT 'UNVERIFIED' CHECK (tax_id_status IN ('UNVERIFIED','FORMAT_VALID','VERIFIED','INVALID','VERIFICATION_UNAVAILABLE')),
    tax_id_verified_at timestamptz,
    verification_ref   varchar(120),          -- e.g. VIES consultation number
    municipal_registration varchar(40),       -- BR inscrição municipal (NFS-e)
    state_registration varchar(40),
    us_state           varchar(2),            -- US nexus jurisdiction of the customer
    tax_exempt         boolean     NOT NULL DEFAULT false,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
