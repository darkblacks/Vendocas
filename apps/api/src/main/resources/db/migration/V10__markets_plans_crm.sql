-- =====================================================================
-- V10: markets, regional plans/prices/quotas, CRM metrics, Sales Registry
-- See docs/produto/projeto-vendocas.md (§3 Mercados, §4 Planos, §6 CRM).
-- Commercial values that are still open decisions (BRL/EUR prices, quotas) are NOT seeded:
-- the platform operator sets them in the Control Center. Until then the plan is not
-- purchasable in that market and WhatsApp/SMS keep the pay-per-use behaviour.
-- =====================================================================

-- ---------- Markets ----------
CREATE TABLE markets (
    code              varchar(10)  PRIMARY KEY CHECK (code IN ('BR','LATAM','NA','EU')),
    name              varchar(60)  NOT NULL,
    currency          varchar(3)   NOT NULL CHECK (currency IN ('USD','BRL','EUR')),
    default_locale    varchar(35)  NOT NULL,
    locales           varchar(300) NOT NULL,          -- comma list (BCP 47)
    channel_order     varchar(60)  NOT NULL,          -- comma list, first = preferred
    send_window_start time         NOT NULL DEFAULT '08:00',
    send_window_end   time         NOT NULL DEFAULT '21:00',
    enabled           boolean      NOT NULL DEFAULT true,
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    updated_by        varchar(200),
    version           bigint       NOT NULL DEFAULT 0
);
INSERT INTO markets (code, name, currency, default_locale, locales, channel_order) VALUES
 ('BR',    'Brasil',             'BRL', 'pt-BR',  'pt-BR',                                        'WHATSAPP,EMAIL,SMS'),
 ('LATAM', 'Latinoamérica',      'USD', 'es-419', 'es-419',                                       'WHATSAPP,EMAIL,SMS'),
 ('NA',    'United States / Canada', 'USD', 'en-US', 'en-US,en-CA,fr-CA',                         'EMAIL,SMS,WHATSAPP'),
 ('EU',    'Europa',             'EUR', 'en-GB',  'es-ES,pt-PT,fr-FR,en-GB,en-IE,it-IT,de-DE',   'EMAIL,WHATSAPP,SMS');

-- ---------- Countries → market ----------
ALTER TABLE supported_countries DROP CONSTRAINT supported_countries_region_check;
ALTER TABLE supported_countries ADD CONSTRAINT supported_countries_region_check CHECK (region IN ('BR','US','EU','LATAM','CA','UK'));
ALTER TABLE supported_countries ADD COLUMN market_code varchar(10) REFERENCES markets(code);
UPDATE supported_countries SET market_code = CASE region WHEN 'BR' THEN 'BR' WHEN 'US' THEN 'NA' ELSE 'EU' END;

INSERT INTO supported_countries (country_code, region, default_currency, default_locale, vat_prefix, market_code) VALUES
 -- LATAM: every Spanish-speaking country of the Americas (Spanish is the default language)
 ('MX','LATAM','USD','es-MX',NULL,'LATAM'),('GT','LATAM','USD','es-GT',NULL,'LATAM'),('SV','LATAM','USD','es-SV',NULL,'LATAM'),
 ('HN','LATAM','USD','es-HN',NULL,'LATAM'),('NI','LATAM','USD','es-NI',NULL,'LATAM'),('CR','LATAM','USD','es-CR',NULL,'LATAM'),
 ('PA','LATAM','USD','es-PA',NULL,'LATAM'),('CU','LATAM','USD','es-CU',NULL,'LATAM'),('DO','LATAM','USD','es-DO',NULL,'LATAM'),
 ('PR','LATAM','USD','es-PR',NULL,'LATAM'),('CO','LATAM','USD','es-CO',NULL,'LATAM'),('VE','LATAM','USD','es-VE',NULL,'LATAM'),
 ('EC','LATAM','USD','es-EC',NULL,'LATAM'),('PE','LATAM','USD','es-PE',NULL,'LATAM'),('BO','LATAM','USD','es-BO',NULL,'LATAM'),
 ('CL','LATAM','USD','es-CL',NULL,'LATAM'),('AR','LATAM','USD','es-AR',NULL,'LATAM'),('UY','LATAM','USD','es-UY',NULL,'LATAM'),
 ('PY','LATAM','USD','es-PY',NULL,'LATAM'),
 -- North America
 ('CA','CA','USD','en-CA',NULL,'NA'),
 -- Europe market beyond the EU-27: United Kingdom and French Guiana
 ('GB','UK','EUR','en-GB',NULL,'EU'),
 ('GF','EU','EUR','fr-GF',NULL,'EU');
ALTER TABLE supported_countries ALTER COLUMN market_code SET NOT NULL;

-- Compliance baseline and channel capabilities for the new countries (same conservative rules as V8).
INSERT INTO compliance_policies (id, policy_version, country_code, channel, purpose, decision, accepted_bases, requires_unsubscribe, quiet_hours_enforced, notes)
SELECT gen_random_uuid(), 1, c.country_code, ch, pu, 'ALLOW_WITH_BASIS',
       CASE WHEN ch = 'WHATSAPP' THEN 'CONSENT' ELSE 'CONSENT,CONTRACT,LEGITIMATE_INTEREST' END,
       false, pu <> 'TRANSACTIONAL', 'v1 baseline: non-promotional communications'
FROM supported_countries c
CROSS JOIN unnest(ARRAY['EMAIL','WHATSAPP','SMS']) ch
CROSS JOIN unnest(ARRAY['TRANSACTIONAL','SERVICE_REMINDER','CUSTOMER_CARE']) pu
WHERE c.region IN ('LATAM','CA','UK') OR c.country_code = 'GF';

INSERT INTO compliance_policies (id, policy_version, country_code, channel, purpose, decision, accepted_bases, requires_unsubscribe, quiet_hours_enforced, notes)
SELECT gen_random_uuid(), 1, c.country_code, ch, pu, 'ALLOW_WITH_BASIS',
       CASE WHEN ch = 'EMAIL' AND c.region <> 'CA' THEN 'CONSENT,EXISTING_CUSTOMER_SOFT_OPT_IN' ELSE 'CONSENT' END,
       true, true,
       CASE WHEN c.region = 'CA' THEN 'v1 baseline: CASL requires express consent for commercial messages'
            ELSE 'v1 baseline: promotional communications' END
FROM supported_countries c
CROSS JOIN unnest(ARRAY['EMAIL','WHATSAPP','SMS']) ch
CROSS JOIN unnest(ARRAY['MARKETING','REACTIVATION']) pu
WHERE c.region IN ('LATAM','CA','UK') OR c.country_code = 'GF';

INSERT INTO channel_capabilities (country_code, channel, capability, available, requires, notes, reviewed_at)
SELECT c.country_code, 'EMAIL', cap, true, 'Verified sending domain or platform subdomain', NULL, DATE '2026-11-26'
FROM supported_countries c CROSS JOIN unnest(ARRAY['SEND_TRANSACTIONAL','SEND_MARKETING','INBOUND']) cap
WHERE c.region IN ('LATAM','CA','UK') OR c.country_code = 'GF';

INSERT INTO channel_capabilities (country_code, channel, capability, available, requires, notes, reviewed_at)
SELECT c.country_code, 'WHATSAPP', cap, true,
       'WhatsApp Business Account via Embedded Signup; approved templates for business-initiated messages', NULL, DATE '2026-11-26'
FROM supported_countries c CROSS JOIN unnest(ARRAY['SEND_TRANSACTIONAL','SEND_MARKETING','INBOUND']) cap
WHERE c.region IN ('LATAM','CA','UK') OR c.country_code = 'GF';

INSERT INTO channel_capabilities (country_code, channel, capability, available, requires, notes, reviewed_at)
SELECT c.country_code, 'SMS', cap, true,
       CASE WHEN c.region = 'CA' THEN 'Registered toll-free or 10DLC-equivalent sender; CASL consent'
            ELSE 'Twilio regulatory bundle / sender registration where the country requires it' END,
       NULL, DATE '2026-11-26'
FROM supported_countries c CROSS JOIN unnest(ARRAY['SEND_TRANSACTIONAL','SEND_MARKETING','INBOUND']) cap
WHERE c.region IN ('LATAM','CA','UK') OR c.country_code = 'GF';

INSERT INTO channel_capabilities (country_code, channel, capability, available, requires, notes, reviewed_at)
SELECT c.country_code, 'SMS', cap, false, NULL, 'Not enabled until per-country rules are verified', DATE '2026-11-26'
FROM supported_countries c CROSS JOIN unnest(ARRAY['MMS','ALPHA_SENDER']) cap
WHERE c.region IN ('LATAM','CA','UK') OR c.country_code = 'GF';

INSERT INTO channel_capabilities (country_code, channel, capability, available, requires, notes, reviewed_at)
SELECT c.country_code, 'VOICE', 'MISSED_CALL', true,
       'Provider number (or conditional forwarding to one) and a successful test call', NULL, DATE '2026-11-26'
FROM supported_countries c WHERE c.region IN ('LATAM','CA','UK') OR c.country_code = 'GF';

-- ---------- Organization → market ----------
ALTER TABLE organizations ADD COLUMN market_code varchar(10) REFERENCES markets(code);
UPDATE organizations o SET market_code = c.market_code FROM supported_countries c WHERE c.country_code = o.billing_country_code;
UPDATE organizations SET market_code = 'NA' WHERE market_code IS NULL;
ALTER TABLE organizations ALTER COLUMN market_code SET NOT NULL;

-- Contact shown in the alert footer ("this message is only an alert — contact the store: …")
ALTER TABLE organization_settings ADD COLUMN alert_contact varchar(200);

-- ---------- Plans: Alerta / Alerta + Vendas / Ilimitado ----------
-- Previous plans stay in the table (existing subscriptions keep working) but are no longer sold.
UPDATE plans SET active = false WHERE code IN ('EMAIL_STARTER','EMAIL_WHATSAPP','COMPLETE_TRIO','DAILY_CAMPAIGNS');

INSERT INTO plans (id, code, name, tier, is_promotional) VALUES
 ('00000000-0000-0000-0000-000000000111','ALERTA','Alerta',10,false),
 ('00000000-0000-0000-0000-000000000112','ALERTA_VENDAS','Alerta + Vendas',20,false),
 ('00000000-0000-0000-0000-000000000113','ILIMITADO','Ilimitado',30,false);

-- ALERTS_ONLY: no campaigns, no conversations, fixed "only an alert" footer on every message.
INSERT INTO plan_features (plan_id, feature)
SELECT '00000000-0000-0000-0000-000000000111'::uuid, f FROM unnest(ARRAY['ALERTS','ALERTS_ONLY','AUTOMATIONS','CRM','REPORTS','CHANNEL_EMAIL','CHANNEL_WHATSAPP','CHANNEL_SMS']) f
UNION ALL
SELECT '00000000-0000-0000-0000-000000000112'::uuid, f FROM unnest(ARRAY['ALERTS','ALERTS_ONLY','AUTOMATIONS','CRM','REPORTS','CHANNEL_EMAIL','CHANNEL_WHATSAPP','CHANNEL_SMS','SALES_REGISTRY']) f
UNION ALL
SELECT '00000000-0000-0000-0000-000000000113'::uuid, f FROM unnest(ARRAY['ALERTS','AUTOMATIONS','CRM','REPORTS','CHANNEL_EMAIL','CHANNEL_WHATSAPP','CHANNEL_SMS','CHANNEL_VOICE',
                                                                         'SALES_REGISTRY','CAMPAIGNS','CAMPAIGNS_DAILY','CUSTOM_SEGMENTS','INBOX']) f;

-- ---------- Prices per market ----------
ALTER TABLE prices ADD COLUMN market_code varchar(10) REFERENCES markets(code);   -- NULL = legacy global price
ALTER TABLE prices ADD COLUMN offer_text varchar(200);
ALTER TABLE prices ADD COLUMN trial_days int NOT NULL DEFAULT 0 CHECK (trial_days BETWEEN 0 AND 90);
ALTER TABLE prices ADD COLUMN updated_by varchar(200);
DROP INDEX ux_prices_active;
CREATE UNIQUE INDEX ux_prices_active ON prices(plan_id, COALESCE(market_code, '*'), currency, billing_interval) WHERE active;

-- USD reference prices (US$19 / US$29 / US$110) for the USD markets. BRL and EUR are set in the Control Center.
INSERT INTO prices (id, plan_id, currency, unit_amount_minor, market_code)
SELECT gen_random_uuid(), p.plan_id, 'USD', p.amount, m.code
FROM (VALUES ('00000000-0000-0000-0000-000000000111'::uuid, 1900::bigint),
             ('00000000-0000-0000-0000-000000000112'::uuid, 2900::bigint),
             ('00000000-0000-0000-0000-000000000113'::uuid, 11000::bigint)) AS p(plan_id, amount)
CROSS JOIN (VALUES ('LATAM'), ('NA')) AS m(code);

-- ---------- Included monthly quotas per plan × market × channel ----------
-- monthly_limit NULL = unlimited (fair use). No row = channel is pay-per-use (usage_rates + credit wallet).
CREATE TABLE plan_quotas (
    plan_id       uuid        NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    market_code   varchar(10) NOT NULL REFERENCES markets(code),
    channel       varchar(20) NOT NULL CHECK (channel IN ('EMAIL','WHATSAPP','SMS')),
    monthly_limit bigint      CHECK (monthly_limit IS NULL OR monthly_limit >= 0),
    fair_use_note varchar(300),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    updated_by    varchar(200),
    PRIMARY KEY (plan_id, market_code, channel)
);

-- A message sent inside the plan's included quota (not charged to the wallet).
ALTER TABLE messages ADD COLUMN quota_included boolean NOT NULL DEFAULT false;
CREATE INDEX ix_messages_quota ON messages(organization_id, channel, created_at) WHERE quota_included;

-- ---------- CRM: stage, RFM and purchase metrics per contact ----------
CREATE TABLE contact_metrics (
    contact_id         uuid PRIMARY KEY REFERENCES contacts(id) ON DELETE CASCADE,
    organization_id    uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    stage              varchar(20) NOT NULL CHECK (stage IN ('VISITOR','NEW','RECURRING','VIP','AT_RISK','INACTIVE')),
    recency_score      smallint CHECK (recency_score BETWEEN 1 AND 5),
    frequency_score    smallint CHECK (frequency_score BETWEEN 1 AND 5),
    monetary_score     smallint CHECK (monetary_score BETWEEN 1 AND 5),
    orders_count       int         NOT NULL DEFAULT 0,
    total_spent_minor  bigint      NOT NULL DEFAULT 0,
    spent_12m_minor    bigint      NOT NULL DEFAULT 0,
    currency           varchar(3),
    first_purchase_at  timestamptz,
    last_purchase_at   timestamptz,
    avg_interval_days  numeric(10,2),
    stage_changed_at   timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_contact_metrics_stage ON contact_metrics(organization_id, stage);

-- Per-organization reference values used to classify a single contact between daily runs.
CREATE TABLE crm_org_stats (
    organization_id      uuid PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    repurchase_days      numeric(10,2) NOT NULL,
    vip_threshold_minor  bigint,               -- NULL = too few buyers to define VIPs
    buyers               int         NOT NULL DEFAULT 0,
    computed_at          timestamptz NOT NULL DEFAULT now()
);

-- ---------- Sales Registry (plan Alerta + Vendas and above) ----------
CREATE TABLE products (
    id                uuid PRIMARY KEY,
    organization_id   uuid         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name              varchar(200) NOT NULL,
    sku               varchar(80),
    price_minor       bigint       NOT NULL CHECK (price_minor >= 0),
    currency          varchar(3)   NOT NULL,
    category          varchar(100),
    active            boolean      NOT NULL DEFAULT true,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_products_sku ON products(organization_id, sku) WHERE sku IS NOT NULL;
CREATE INDEX ix_products_org ON products(organization_id, active, name);

ALTER TABLE orders ADD COLUMN sales_channel varchar(20) CHECK (sales_channel IN ('STORE','WHATSAPP','INSTAGRAM','ONLINE','PHONE','OTHER'));
ALTER TABLE orders ADD COLUMN payment_method varchar(20) CHECK (payment_method IN ('PIX','CARD','CASH','BOLETO','TRANSFER','OTHER'));
ALTER TABLE orders ADD COLUMN registered_by uuid REFERENCES users(id);
ALTER TABLE orders ADD COLUMN notes varchar(500);

CREATE TABLE order_items (
    id                uuid PRIMARY KEY,
    order_id          uuid         NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    organization_id   uuid         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    product_id        uuid REFERENCES products(id) ON DELETE SET NULL,
    description       varchar(200) NOT NULL,
    quantity          int          NOT NULL CHECK (quantity > 0),
    unit_amount_minor bigint       NOT NULL CHECK (unit_amount_minor >= 0)
);
CREATE INDEX ix_order_items_order ON order_items(order_id);
CREATE INDEX ix_order_items_product ON order_items(organization_id, product_id);
