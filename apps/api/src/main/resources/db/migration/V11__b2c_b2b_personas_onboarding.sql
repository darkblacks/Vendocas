-- =====================================================================
-- V11: product decisions of 2026-11-27
--  * United States and Canada become separate price markets (US, CA).
--  * Plan features: Sales registry (PDV) in every plan — "venda seca" (customer + amount) on Alerta;
--    product catalogue, Personas and the B2C/B2B kanbans from Alerta + Vendas up.
--  * B2C pipeline stages, automatic and per customer: first sale → recurring → in window → churn risk → churn.
--  * B2B pipeline: hand-moved deals (lead → proposal → negotiation → won / lost).
--  * Personas (customer profiles linked to products), mandatory onboarding, "has WhatsApp?" on contacts.
-- =====================================================================

-- ---------- Markets: NA → US + CA ----------
ALTER TABLE markets DROP CONSTRAINT markets_code_check;
ALTER TABLE markets ADD CONSTRAINT markets_code_check CHECK (code IN ('BR','LATAM','NA','US','CA','EU'));
INSERT INTO markets (code, name, currency, default_locale, locales, channel_order, send_window_start, send_window_end, enabled)
SELECT 'US', 'United States', currency, 'en-US', 'en-US', channel_order, send_window_start, send_window_end, enabled FROM markets WHERE code = 'NA';
INSERT INTO markets (code, name, currency, default_locale, locales, channel_order, send_window_start, send_window_end, enabled)
SELECT 'CA', 'Canada', currency, 'en-CA', 'en-CA,fr-CA', channel_order, send_window_start, send_window_end, enabled FROM markets WHERE code = 'NA';

UPDATE supported_countries SET market_code = country_code WHERE country_code IN ('US','CA');
UPDATE organizations SET market_code = CASE WHEN billing_country_code = 'CA' THEN 'CA' ELSE 'US' END WHERE market_code = 'NA';
-- Existing NA prices become the US prices (subscriptions keep pointing at them); Canada starts with a copy.
INSERT INTO prices (id, plan_id, currency, unit_amount_minor, billing_interval, tax_behavior, stripe_price_id, active, valid_from, market_code, offer_text, trial_days, updated_by)
SELECT gen_random_uuid(), plan_id, currency, unit_amount_minor, billing_interval, tax_behavior, NULL, active, valid_from, 'CA', offer_text, trial_days, 'migration V11'
FROM prices WHERE market_code = 'NA' AND active;
UPDATE prices SET market_code = 'US' WHERE market_code = 'NA';
INSERT INTO plan_quotas (plan_id, market_code, channel, monthly_limit, fair_use_note, updated_by)
SELECT plan_id, 'CA', channel, monthly_limit, fair_use_note, 'migration V11' FROM plan_quotas WHERE market_code = 'NA';
UPDATE plan_quotas SET market_code = 'US' WHERE market_code = 'NA';
DELETE FROM markets WHERE code = 'NA';
ALTER TABLE markets DROP CONSTRAINT markets_code_check;
ALTER TABLE markets ADD CONSTRAINT markets_code_check CHECK (code IN ('BR','LATAM','US','CA','EU'));

-- ---------- Plan features ----------
-- Alerta (19,90): PDV "venda seca".  Alerta + Vendas (29,90) and Ilimitado: catalogue, Personas, kanbans.
INSERT INTO plan_features (plan_id, feature)
SELECT p.id, f FROM plans p CROSS JOIN unnest(ARRAY['SALES_REGISTRY']) f WHERE p.code = 'ALERTA'
UNION ALL
SELECT p.id, f FROM plans p CROSS JOIN unnest(ARRAY['PRODUCT_CATALOG','PERSONAS','PIPELINE']) f WHERE p.code IN ('ALERTA_VENDAS','ILIMITADO')
ON CONFLICT DO NOTHING;

-- ---------- B2C stages (per-customer window) ----------
ALTER TABLE contact_metrics DROP CONSTRAINT contact_metrics_stage_check;
UPDATE contact_metrics SET stage = CASE stage WHEN 'NEW' THEN 'FIRST_SALE' WHEN 'VIP' THEN 'RECURRING' WHEN 'AT_RISK' THEN 'CHURN_RISK'
                                             WHEN 'INACTIVE' THEN 'CHURNED' ELSE stage END;
ALTER TABLE contact_metrics ADD CONSTRAINT contact_metrics_stage_check
    CHECK (stage IN ('VISITOR','FIRST_SALE','RECURRING','IN_WINDOW','CHURN_RISK','CHURNED'));
ALTER TABLE contact_metrics ADD COLUMN vip boolean NOT NULL DEFAULT false;
ALTER TABLE contact_metrics ADD COLUMN window_days numeric(10,2);          -- this customer's usual return interval
ALTER TABLE contact_metrics ADD COLUMN next_expected_at timestamptz;       -- last purchase + window

-- ---------- "Has WhatsApp?" ----------
ALTER TABLE contacts ADD COLUMN whatsapp_status varchar(10) NOT NULL DEFAULT 'UNKNOWN' CHECK (whatsapp_status IN ('YES','NO','UNKNOWN'));

-- ---------- B2B pipeline ----------
CREATE TABLE deals (
    id               uuid PRIMARY KEY,
    organization_id  uuid         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    contact_id       uuid REFERENCES contacts(id) ON DELETE SET NULL,
    company_name     varchar(200),
    title            varchar(200) NOT NULL,
    stage            varchar(20)  NOT NULL DEFAULT 'LEAD' CHECK (stage IN ('LEAD','PROPOSAL','NEGOTIATION','WON','LOST')),
    position         double precision NOT NULL DEFAULT 0,
    value_minor      bigint CHECK (value_minor IS NULL OR value_minor >= 0),
    currency         varchar(3),
    expected_close_on date,
    notes            varchar(2000),
    lost_reason      varchar(300),
    owner_user_id    uuid REFERENCES users(id) ON DELETE SET NULL,
    closed_at        timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_deals_board ON deals(organization_id, stage, position);

-- ---------- Personas ----------
CREATE TABLE personas (
    id               uuid PRIMARY KEY,
    organization_id  uuid         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name             varchar(80)  NOT NULL,
    description      varchar(500),
    color            varchar(9),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name)
);
CREATE TABLE persona_products (
    persona_id  uuid NOT NULL REFERENCES personas(id) ON DELETE CASCADE,
    product_id  uuid NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    PRIMARY KEY (persona_id, product_id)
);
CREATE TABLE contact_personas (
    contact_id  uuid NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    persona_id  uuid NOT NULL REFERENCES personas(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (contact_id, persona_id)
);
CREATE INDEX ix_contact_personas_persona ON contact_personas(persona_id);

-- ---------- Mandatory onboarding ----------
CREATE TABLE organization_onboarding (
    organization_id  uuid PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    business_segment varchar(80),
    whatsapp_choice  varchar(20) CHECK (whatsapp_choice IN ('CONNECT_OWN','PROVISION_NUMBER')),
    email_choice     varchar(20) CHECK (email_choice IN ('OWN_ADDRESS','VENDOCAS_ADDRESS')),
    email_address    varchar(254),
    completed_at     timestamptz,
    updated_at       timestamptz NOT NULL DEFAULT now()
);
-- Organizations that already use the product are not sent back to onboarding.
INSERT INTO organization_onboarding (organization_id, completed_at)
SELECT id, now() FROM organizations ON CONFLICT DO NOTHING;
