-- =====================================================================
-- V2: plans, prices, subscriptions, invoices, payments, credits
-- Prices are authoritative here (and mirrored in Stripe); the frontend never sends amounts.
-- =====================================================================
CREATE TABLE plans (
    id            uuid PRIMARY KEY,
    code          varchar(40)  NOT NULL UNIQUE,   -- EMAIL_STARTER, EMAIL_WHATSAPP, COMPLETE_TRIO, DAILY_CAMPAIGNS
    name          varchar(120) NOT NULL,
    tier          int          NOT NULL UNIQUE,   -- ordering for upgrade/downgrade
    is_promotional boolean     NOT NULL DEFAULT false,
    active        boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE plan_features (
    plan_id     uuid        NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    feature     varchar(40) NOT NULL,  -- CHANNEL_EMAIL, CHANNEL_WHATSAPP, CHANNEL_SMS, CHANNEL_VOICE, CAMPAIGNS_DAILY, AUTOMATIONS, REPORTS
    limit_value bigint,                -- NULL = no numeric limit for this feature
    PRIMARY KEY (plan_id, feature)
);

CREATE TABLE prices (
    id                uuid PRIMARY KEY,
    plan_id           uuid        NOT NULL REFERENCES plans(id),
    currency          varchar(3)     NOT NULL CHECK (currency IN ('USD','BRL','EUR')),
    unit_amount_minor bigint      NOT NULL CHECK (unit_amount_minor > 0),
    billing_interval  varchar(10) NOT NULL DEFAULT 'MONTH' CHECK (billing_interval IN ('MONTH')),
    tax_behavior      varchar(12) NOT NULL DEFAULT 'EXCLUSIVE' CHECK (tax_behavior IN ('EXCLUSIVE','INCLUSIVE')),
    stripe_price_id   varchar(120),        -- set by ops after creating the Price in Stripe
    active            boolean     NOT NULL DEFAULT true,
    valid_from        timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_prices_active ON prices(plan_id, currency, billing_interval) WHERE active;

CREATE TABLE billing_customers (
    organization_id     uuid PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    provider            varchar(20)  NOT NULL DEFAULT 'STRIPE',
    provider_customer_id varchar(120) NOT NULL UNIQUE,
    created_at          timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE subscriptions (
    id                    uuid PRIMARY KEY,
    organization_id       uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    plan_id               uuid        NOT NULL REFERENCES plans(id),
    price_id              uuid        NOT NULL REFERENCES prices(id),
    status                varchar(20) NOT NULL CHECK (status IN ('DRAFT','CHECKOUT_PENDING','PAYMENT_PENDING','ACTIVE','PAST_DUE','GRACE_PERIOD','SUSPENDED','CANCELED','EXPIRED')),
    collection_method     varchar(20) NOT NULL CHECK (collection_method IN ('CHARGE_AUTOMATICALLY','SEND_INVOICE')),
    provider              varchar(20) NOT NULL DEFAULT 'STRIPE',
    provider_subscription_id varchar(120) UNIQUE,
    provider_checkout_id  varchar(120),
    current_period_start  timestamptz,
    current_period_end    timestamptz,
    grace_until           timestamptz,
    cancel_at_period_end  boolean     NOT NULL DEFAULT false,
    canceled_at           timestamptz,
    pending_plan_id       uuid REFERENCES plans(id),  -- scheduled downgrade applied at period end
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    version               bigint      NOT NULL DEFAULT 0
);
-- One non-terminal subscription per establishment (organization)
CREATE UNIQUE INDEX ux_subscription_live ON subscriptions(organization_id) WHERE status NOT IN ('CANCELED','EXPIRED');

CREATE TABLE subscription_events (
    id               uuid PRIMARY KEY,
    subscription_id  uuid        NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    organization_id  uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    from_status      varchar(20),
    to_status        varchar(20) NOT NULL,
    from_plan_id     uuid,
    to_plan_id       uuid,
    reason           varchar(200) NOT NULL,
    actor            varchar(80)  NOT NULL,   -- user:<id> | provider:stripe | system:scheduler
    provider_event_id varchar(120),
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_sub_events_sub ON subscription_events(subscription_id, created_at);

CREATE TABLE payment_methods (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    provider          varchar(20) NOT NULL DEFAULT 'STRIPE',
    provider_pm_id    varchar(120) NOT NULL UNIQUE,  -- token only; never PAN/CVV
    type              varchar(30) NOT NULL,          -- card, sepa_debit, us_bank_account, boleto ...
    brand             varchar(30),
    last4             varchar(4),
    exp_month         int,
    exp_year          int,
    is_default        boolean     NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE invoices (
    id                  uuid PRIMARY KEY,
    organization_id     uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    subscription_id     uuid REFERENCES subscriptions(id),
    kind                varchar(20) NOT NULL CHECK (kind IN ('SUBSCRIPTION','CREDITS','ADJUSTMENT')),
    status              varchar(20) NOT NULL CHECK (status IN ('DRAFT','OPEN','PAID','VOID','UNCOLLECTIBLE')),
    collection_method   varchar(20) NOT NULL CHECK (collection_method IN ('CHARGE_AUTOMATICALLY','SEND_INVOICE')),
    currency            varchar(3)     NOT NULL,
    subtotal_minor      bigint      NOT NULL,
    tax_minor           bigint      NOT NULL DEFAULT 0,
    total_minor         bigint      NOT NULL,
    amount_paid_minor   bigint      NOT NULL DEFAULT 0,
    amount_due_minor    bigint      NOT NULL,
    reverse_charge      boolean     NOT NULL DEFAULT false,
    due_at              timestamptz,
    paid_at             timestamptz,
    period_start        timestamptz,
    period_end          timestamptz,
    number              varchar(60),
    provider            varchar(20) NOT NULL DEFAULT 'STRIPE',
    provider_invoice_id varchar(120) UNIQUE,
    hosted_url          varchar(1000),
    pdf_url             varchar(1000),           -- billing document; NOT a fiscal document
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_invoices_org ON invoices(organization_id, created_at DESC);
CREATE INDEX ix_invoices_open_due ON invoices(due_at) WHERE status = 'OPEN';

CREATE TABLE invoice_items (
    id             uuid PRIMARY KEY,
    invoice_id     uuid         NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    description    varchar(300) NOT NULL,
    quantity       int          NOT NULL DEFAULT 1,
    amount_minor   bigint       NOT NULL,
    tax_minor      bigint       NOT NULL DEFAULT 0,
    tax_rate_bps   int,                      -- basis points, as returned by the tax engine
    tax_jurisdiction varchar(60),
    proration      boolean      NOT NULL DEFAULT false
);

CREATE TABLE payments (
    id                  uuid PRIMARY KEY,
    organization_id     uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    invoice_id          uuid REFERENCES invoices(id),
    status              varchar(20) NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED','REFUNDED','PARTIALLY_REFUNDED','DISPUTED')),
    method_type         varchar(30),
    currency            varchar(3)     NOT NULL,
    amount_minor        bigint      NOT NULL,
    refunded_minor      bigint      NOT NULL DEFAULT 0,
    provider            varchar(20) NOT NULL DEFAULT 'STRIPE',
    provider_payment_id varchar(120) UNIQUE,
    failure_code        varchar(80),
    failure_message     varchar(500),
    settled_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE payment_attempts (
    id                uuid PRIMARY KEY,
    payment_id        uuid        NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    attempt_no        int         NOT NULL,
    outcome           varchar(20) NOT NULL,
    provider_ref      varchar(120),
    detail            varchar(500),
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (payment_id, attempt_no)
);

-- Fiscal documents (NFS-e etc.) are distinct from billing invoices.
CREATE TABLE tax_documents (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    invoice_id         uuid        NOT NULL REFERENCES invoices(id),
    document_type      varchar(20) NOT NULL CHECK (document_type IN ('BR_NFSE','EU_EINVOICE','US_RECEIPT')),
    status             varchar(20) NOT NULL CHECK (status IN ('PENDING','PROCESSING','ISSUED','REJECTED','CANCELED','NOT_CONFIGURED')),
    provider           varchar(30) NOT NULL,
    provider_ref       varchar(120) NOT NULL UNIQUE,  -- idempotent reference sent to the fiscal provider
    number             varchar(60),
    verification_code  varchar(120),
    document_object_key varchar(500),
    xml_object_key     varchar(500),
    error_message      varchar(1000),
    issued_at          timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (invoice_id, document_type)
);

CREATE TABLE credit_wallets (
    organization_id     uuid PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    currency            varchar(3)     NOT NULL,
    balance_minor       bigint      NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    reserved_minor      bigint      NOT NULL DEFAULT 0 CHECK (reserved_minor >= 0),
    low_balance_notified_at timestamptz,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint      NOT NULL DEFAULT 0,
    CHECK (reserved_minor <= balance_minor)
);

CREATE TABLE credit_transactions (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    type              varchar(20) NOT NULL CHECK (type IN ('PURCHASE','GRANT','RESERVE','RELEASE','DEBIT','ADJUSTMENT','REFUND')),
    amount_minor      bigint      NOT NULL,   -- signed effect on balance (RESERVE/RELEASE affect reserved only)
    balance_after     bigint      NOT NULL,
    reserved_after    bigint      NOT NULL,
    reference_type    varchar(30),            -- MESSAGE, CAMPAIGN_RUN, INVOICE ...
    reference_id      uuid,
    idempotency_key   varchar(160) NOT NULL UNIQUE,
    description       varchar(300),
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_credit_tx_org ON credit_transactions(organization_id, created_at DESC);

-- What we charge vs what the provider charges us (reconciled asynchronously)
CREATE TABLE provider_costs (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    provider           varchar(30) NOT NULL,
    channel            varchar(20) NOT NULL,
    reference_type     varchar(30) NOT NULL,
    reference_id       uuid        NOT NULL,
    estimated_cost_micros bigint,           -- provider cost in USD micros
    actual_cost_micros bigint,
    charged_minor      bigint      NOT NULL, -- what the customer was debited
    charged_currency   varchar(3)     NOT NULL,
    reconciled_at      timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (reference_type, reference_id)
);

-- Unit prices we charge per channel/country (customer-facing), managed by platform admins
CREATE TABLE usage_rates (
    id               uuid PRIMARY KEY,
    channel          varchar(20) NOT NULL,
    country_code     varchar(2),                 -- NULL = default for the channel
    message_category varchar(20) NOT NULL DEFAULT 'ANY',
    currency         varchar(3)     NOT NULL,
    unit_price_minor bigint      NOT NULL,     -- per message segment / per minute
    provider_cost_micros bigint,              -- reference provider cost (USD micros)
    active           boolean     NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_usage_rates ON usage_rates(channel, COALESCE(country_code,'**'), message_category, currency) WHERE active;
