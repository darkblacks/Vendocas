-- =====================================================================
-- V9: independent environment / payment / fiscal settings; Billing ↔ Fiscal decoupling.
-- Additive only: existing rows and API contracts are preserved.
-- =====================================================================

-- Singleton, versioned (optimistic concurrency) platform configuration.
-- Defaults are the safe ones: homologation, Stripe test mode, no automatic fiscal issuance.
CREATE TABLE platform_settings (
    id                 smallint     PRIMARY KEY CHECK (id = 1),
    environment        varchar(20)  NOT NULL DEFAULT 'HOMOLOGATION' CHECK (environment IN ('HOMOLOGATION','PRODUCTION')),
    stripe_mode        varchar(10)  NOT NULL DEFAULT 'TEST' CHECK (stripe_mode IN ('TEST','LIVE')),
    fiscal_automation  boolean      NOT NULL DEFAULT false,
    fiscal_provider    varchar(20)  NOT NULL DEFAULT 'NONE' CHECK (fiscal_provider IN ('NONE','FOCUS_NFE')),
    version            int          NOT NULL DEFAULT 1,
    updated_by         varchar(200),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);
INSERT INTO platform_settings (id) VALUES (1);

-- Reconciliation: which deployment environment and which Stripe mode produced each record.
-- NULL = recorded before this migration (unknown).
ALTER TABLE payments ADD COLUMN livemode boolean;
ALTER TABLE payments ADD COLUMN environment varchar(20) CHECK (environment IN ('HOMOLOGATION','PRODUCTION'));
ALTER TABLE invoices ADD COLUMN livemode boolean;

-- Fiscal obligations are tracked even when automatic issuance is off:
--   AWAITING_MANUAL  automation disabled — must be issued manually/later (the obligation is NOT waived)
--   ISSUED with provider MANUAL — issued outside the platform and recorded by an operator
ALTER TABLE tax_documents DROP CONSTRAINT tax_documents_status_check;
ALTER TABLE tax_documents ADD CONSTRAINT tax_documents_status_check
    CHECK (status IN ('PENDING','PROCESSING','ISSUED','REJECTED','CANCELED','NOT_CONFIGURED','AWAITING_MANUAL'));
ALTER TABLE tax_documents ADD COLUMN payment_id uuid REFERENCES payments(id);
ALTER TABLE tax_documents ADD COLUMN needs_review boolean NOT NULL DEFAULT false;
ALTER TABLE tax_documents ADD COLUMN review_reason varchar(300);
ALTER TABLE tax_documents ADD COLUMN notes varchar(1000);
CREATE INDEX ix_tax_documents_status ON tax_documents(status);
