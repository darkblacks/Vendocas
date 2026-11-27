-- V12: the store's sales model, chosen in the onboarding from the type of establishment (pre-set per type, editable).
-- B2C = customers come, buy and leave → automatic board driven by the sales registered in the PDV.
-- B2B = sells to companies → hand-moved negotiation funnel.
ALTER TABLE organization_onboarding ADD COLUMN sales_model varchar(3) CHECK (sales_model IN ('B2C','B2B'));
UPDATE organization_onboarding SET sales_model = 'B2C' WHERE sales_model IS NULL AND completed_at IS NOT NULL;
COMMENT ON COLUMN organization_onboarding.business_segment IS 'Type of establishment (key from the web catalogue, e.g. pizzaria, aesthetics_clinic)';
