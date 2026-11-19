-- =====================================================================
-- V8: reference data — plans, features, USD prices, permissions, policies, capabilities
-- Regional BRL/EUR prices are NOT seeded: they are a commercial decision and must be
-- created by a platform admin (Admin → Plans & prices) together with the Stripe Price id.
-- Messaging usage rates for WhatsApp/SMS are NOT seeded for the same reason; until they
-- exist the engine blocks paid sends with reason USAGE_RATE_NOT_CONFIGURED.
-- =====================================================================
INSERT INTO plans (id, code, name, tier, is_promotional) VALUES
 ('00000000-0000-0000-0000-000000000101','EMAIL_STARTER','Email Starter',1,false),
 ('00000000-0000-0000-0000-000000000102','EMAIL_WHATSAPP','Email + WhatsApp',2,false),
 ('00000000-0000-0000-0000-000000000103','COMPLETE_TRIO','Complete Trio',3,true),
 ('00000000-0000-0000-0000-000000000104','DAILY_CAMPAIGNS','Daily Campaigns',4,false);

INSERT INTO plan_features (plan_id, feature) VALUES
 ('00000000-0000-0000-0000-000000000101','CHANNEL_EMAIL'),
 ('00000000-0000-0000-0000-000000000101','AUTOMATIONS'),
 ('00000000-0000-0000-0000-000000000101','REPORTS'),
 ('00000000-0000-0000-0000-000000000102','CHANNEL_EMAIL'),
 ('00000000-0000-0000-0000-000000000102','CHANNEL_WHATSAPP'),
 ('00000000-0000-0000-0000-000000000102','AUTOMATIONS'),
 ('00000000-0000-0000-0000-000000000102','REPORTS'),
 ('00000000-0000-0000-0000-000000000103','CHANNEL_EMAIL'),
 ('00000000-0000-0000-0000-000000000103','CHANNEL_WHATSAPP'),
 ('00000000-0000-0000-0000-000000000103','CHANNEL_SMS'),
 ('00000000-0000-0000-0000-000000000103','CHANNEL_VOICE'),
 ('00000000-0000-0000-0000-000000000103','AUTOMATIONS'),
 ('00000000-0000-0000-0000-000000000103','REPORTS'),
 ('00000000-0000-0000-0000-000000000104','CHANNEL_EMAIL'),
 ('00000000-0000-0000-0000-000000000104','CHANNEL_WHATSAPP'),
 ('00000000-0000-0000-0000-000000000104','CHANNEL_SMS'),
 ('00000000-0000-0000-0000-000000000104','CHANNEL_VOICE'),
 ('00000000-0000-0000-0000-000000000104','AUTOMATIONS'),
 ('00000000-0000-0000-0000-000000000104','REPORTS'),
 ('00000000-0000-0000-0000-000000000104','CAMPAIGNS_DAILY');

INSERT INTO prices (id, plan_id, currency, unit_amount_minor) VALUES
 ('00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-000000000101','USD',1000),
 ('00000000-0000-0000-0000-000000000202','00000000-0000-0000-0000-000000000102','USD',1500),
 ('00000000-0000-0000-0000-000000000203','00000000-0000-0000-0000-000000000103','USD',1800),
 ('00000000-0000-0000-0000-000000000204','00000000-0000-0000-0000-000000000104','USD',12000);

-- Email sending is included in the subscription (subject to per-establishment limits)
INSERT INTO usage_rates (id, channel, country_code, message_category, currency, unit_price_minor, provider_cost_micros)
SELECT gen_random_uuid(), 'EMAIL', NULL, 'ANY', cur, 0, 100 FROM unnest(ARRAY['USD','BRL','EUR']) cur;

-- Role → permission matrix (mirror of com.platform.tenants.Role)
INSERT INTO role_permissions (role, permission)
SELECT 'OWNER', p FROM unnest(ARRAY['ORG_READ','ORG_MANAGE','MEMBERS_MANAGE','BILLING_READ','BILLING_MANAGE','CONTACTS_READ','CONTACTS_WRITE','CONTACTS_EXPORT','CONSENT_MANAGE','CHANNELS_READ','CHANNELS_MANAGE','AUTOMATIONS_READ','AUTOMATIONS_WRITE','AUTOMATIONS_ACTIVATE','CAMPAIGNS_READ','CAMPAIGNS_WRITE','CONVERSATIONS_READ','CONVERSATIONS_REPLY','CALLS_READ','CALLS_MANAGE','REPORTS_READ','PRIVACY_MANAGE','INTEGRATIONS_MANAGE','AUDIT_READ']) p
UNION ALL
SELECT 'ADMIN', p FROM unnest(ARRAY['ORG_READ','ORG_MANAGE','MEMBERS_MANAGE','BILLING_READ','CONTACTS_READ','CONTACTS_WRITE','CONTACTS_EXPORT','CONSENT_MANAGE','CHANNELS_READ','CHANNELS_MANAGE','AUTOMATIONS_READ','AUTOMATIONS_WRITE','AUTOMATIONS_ACTIVATE','CAMPAIGNS_READ','CAMPAIGNS_WRITE','CONVERSATIONS_READ','CONVERSATIONS_REPLY','CALLS_READ','CALLS_MANAGE','REPORTS_READ','PRIVACY_MANAGE','INTEGRATIONS_MANAGE','AUDIT_READ']) p
UNION ALL
SELECT 'FINANCE', p FROM unnest(ARRAY['ORG_READ','BILLING_READ','BILLING_MANAGE','REPORTS_READ','AUDIT_READ']) p
UNION ALL
SELECT 'OPERATOR', p FROM unnest(ARRAY['ORG_READ','CONTACTS_READ','CONTACTS_WRITE','CONSENT_MANAGE','CHANNELS_READ','AUTOMATIONS_READ','AUTOMATIONS_WRITE','CAMPAIGNS_READ','CAMPAIGNS_WRITE','CONVERSATIONS_READ','CONVERSATIONS_REPLY','CALLS_READ','CALLS_MANAGE','REPORTS_READ']) p
UNION ALL
SELECT 'READ_ONLY', p FROM unnest(ARRAY['ORG_READ','CONTACTS_READ','CHANNELS_READ','AUTOMATIONS_READ','CAMPAIGNS_READ','CONVERSATIONS_READ','CALLS_READ','REPORTS_READ']) p;

-- Supported markets: Brazil, United States, EU-27
CREATE TABLE supported_countries (
    country_code  varchar(2) PRIMARY KEY,
    region        varchar(10) NOT NULL CHECK (region IN ('BR','US','EU')),
    default_currency varchar(3) NOT NULL,
    default_locale varchar(35) NOT NULL,
    vat_prefix    varchar(2)
);
INSERT INTO supported_countries VALUES
 ('BR','BR','BRL','pt-BR',NULL),('US','US','USD','en-US',NULL),
 ('AT','EU','EUR','de-AT','AT'),('BE','EU','EUR','nl-BE','BE'),('BG','EU','EUR','bg-BG','BG'),
 ('HR','EU','EUR','hr-HR','HR'),('CY','EU','EUR','el-CY','CY'),('CZ','EU','EUR','cs-CZ','CZ'),
 ('DK','EU','EUR','da-DK','DK'),('EE','EU','EUR','et-EE','EE'),('FI','EU','EUR','fi-FI','FI'),
 ('FR','EU','EUR','fr-FR','FR'),('DE','EU','EUR','de-DE','DE'),('GR','EU','EUR','el-GR','EL'),
 ('HU','EU','EUR','hu-HU','HU'),('IE','EU','EUR','en-IE','IE'),('IT','EU','EUR','it-IT','IT'),
 ('LV','EU','EUR','lv-LV','LV'),('LT','EU','EUR','lt-LT','LT'),('LU','EU','EUR','fr-LU','LU'),
 ('MT','EU','EUR','mt-MT','MT'),('NL','EU','EUR','nl-NL','NL'),('PL','EU','EUR','pl-PL','PL'),
 ('PT','EU','EUR','pt-PT','PT'),('RO','EU','EUR','ro-RO','RO'),('SK','EU','EUR','sk-SK','SK'),
 ('SI','EU','EUR','sl-SI','SI'),('ES','EU','EUR','es-ES','ES'),('SE','EU','EUR','sv-SE','SE');
-- Note: billing in EUR is offered to all EU members; countries with a local currency
-- (e.g. PL, SE, CZ, DK, HU, RO) may get local-currency prices later.

-- ---------- Compliance policy matrix v1 (conservative; requires legal review) ----------
-- Non-marketing purposes: consent, contract or legitimate interest (WhatsApp: opt-in required by Meta)
INSERT INTO compliance_policies (id, policy_version, country_code, channel, purpose, decision, accepted_bases, requires_unsubscribe, quiet_hours_enforced, notes)
SELECT gen_random_uuid(), 1, c.country_code, ch, pu, 'ALLOW_WITH_BASIS',
       CASE WHEN ch = 'WHATSAPP' THEN 'CONSENT' ELSE 'CONSENT,CONTRACT,LEGITIMATE_INTEREST' END,
       false, pu <> 'TRANSACTIONAL', 'v1 baseline: non-promotional communications'
FROM supported_countries c
CROSS JOIN unnest(ARRAY['EMAIL','WHATSAPP','SMS']) ch
CROSS JOIN unnest(ARRAY['TRANSACTIONAL','SERVICE_REMINDER','CUSTOMER_CARE']) pu;

-- Marketing / reactivation: email = consent or existing-customer soft opt-in (ePrivacy art.13(2) style);
-- SMS = explicit consent only (TCPA prior express written consent in the US);
-- WhatsApp = opt-in consent; US WhatsApp marketing templates blocked (Meta restriction — re-verify before enabling).
INSERT INTO compliance_policies (id, policy_version, country_code, channel, purpose, decision, accepted_bases, requires_unsubscribe, quiet_hours_enforced, notes)
SELECT gen_random_uuid(), 1, c.country_code, ch, pu,
       CASE WHEN ch = 'WHATSAPP' AND c.country_code = 'US' THEN 'BLOCK' ELSE 'ALLOW_WITH_BASIS' END,
       CASE WHEN ch = 'EMAIL' THEN 'CONSENT,EXISTING_CUSTOMER_SOFT_OPT_IN'
            WHEN ch = 'WHATSAPP' AND c.country_code = 'US' THEN ''
            ELSE 'CONSENT' END,
       true, true,
       CASE WHEN ch = 'WHATSAPP' AND c.country_code = 'US' THEN 'Meta marketing templates to US numbers restricted; verify current policy before enabling'
            ELSE 'v1 baseline: promotional communications' END
FROM supported_countries c
CROSS JOIN unnest(ARRAY['EMAIL','WHATSAPP','SMS']) ch
CROSS JOIN unnest(ARRAY['MARKETING','REACTIVATION']) pu;

-- ---------- Channel capability matrix (reviewed 2026-11-19; unknown = unavailable) ----------
INSERT INTO channel_capabilities (country_code, channel, capability, available, requires, notes, reviewed_at)
SELECT c.country_code, 'EMAIL', cap, true, 'Verified sending domain or platform subdomain', NULL, DATE '2026-11-19'
FROM supported_countries c CROSS JOIN unnest(ARRAY['SEND_TRANSACTIONAL','SEND_MARKETING','INBOUND']) cap;

INSERT INTO channel_capabilities (country_code, channel, capability, available, requires, notes, reviewed_at)
SELECT c.country_code, 'WHATSAPP', cap,
       NOT (cap = 'SEND_MARKETING' AND c.country_code = 'US'),
       'WhatsApp Business Account via Embedded Signup; approved templates for business-initiated messages',
       CASE WHEN cap = 'SEND_MARKETING' AND c.country_code = 'US' THEN 'Marketing templates to US numbers restricted by Meta' END,
       DATE '2026-11-19'
FROM supported_countries c CROSS JOIN unnest(ARRAY['SEND_TRANSACTIONAL','SEND_MARKETING','INBOUND']) cap;

INSERT INTO channel_capabilities (country_code, channel, capability, available, requires, notes, reviewed_at)
SELECT c.country_code, 'SMS', cap, true,
       CASE WHEN c.country_code = 'US' THEN 'A2P 10DLC brand + campaign registration (or verified toll-free)'
            WHEN c.country_code = 'BR' THEN 'Twilio regulatory bundle / sender registration'
            ELSE 'Twilio regulatory bundle where required by the country' END,
       NULL, DATE '2026-11-19'
FROM supported_countries c CROSS JOIN unnest(ARRAY['SEND_TRANSACTIONAL','SEND_MARKETING','INBOUND']) cap;

INSERT INTO channel_capabilities (country_code, channel, capability, available, requires, notes, reviewed_at)
SELECT c.country_code, 'SMS', 'MMS', c.country_code = 'US',
       CASE WHEN c.country_code = 'US' THEN 'MMS-capable US long code / toll-free number' END,
       CASE WHEN c.country_code <> 'US' THEN 'MMS not offered; image campaigns fall back to a link when authorized' END,
       DATE '2026-11-19'
FROM supported_countries c;

INSERT INTO channel_capabilities (country_code, channel, capability, available, requires, notes, reviewed_at)
SELECT c.country_code, 'SMS', 'ALPHA_SENDER', false, NULL,
       'Not enabled until per-country registration rules are verified', DATE '2026-11-19'
FROM supported_countries c;

INSERT INTO channel_capabilities (country_code, channel, capability, available, requires, notes, reviewed_at)
SELECT c.country_code, 'VOICE', 'MISSED_CALL', true,
       'Provider number (or conditional forwarding to one) and a successful test call', NULL, DATE '2026-11-19'
FROM supported_countries c;
