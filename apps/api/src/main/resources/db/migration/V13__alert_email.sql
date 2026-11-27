-- Alert footer: the store name on top, then the e-mail and the phone the customer can use to reach the store.
-- alert_contact keeps the phone; alert_email is new (onboarding pre-fills it with the login e-mail).
ALTER TABLE organization_settings ADD COLUMN alert_email varchar(254);
COMMENT ON COLUMN organization_settings.alert_contact IS 'Phone shown in the alert footer (falls back to organizations.contact_phone)';
COMMENT ON COLUMN organization_settings.alert_email IS 'E-mail shown in the alert footer (falls back to organizations.contact_email)';
