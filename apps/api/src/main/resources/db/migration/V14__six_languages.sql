-- Six platform languages, no regional variants: pt, en, es, fr, de, it.
-- Rule (com.platform.shared.i18n.Languages): Brazil/Portugal → pt; Spain and Latin America → es; France (+ French Guiana,
-- Monaco) → fr; Germany/Austria → de; Italy → it; US, Canada, UK, Ireland and every other country → en.
CREATE OR REPLACE FUNCTION platform_language(country varchar) RETURNS varchar LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
    WHEN upper(country) IN ('BR','PT') THEN 'pt'
    WHEN upper(country) IN ('ES','MX','GT','SV','HN','NI','CR','PA','CU','DO','PR','CO','VE','EC','PE','BO','CL','AR','UY','PY') THEN 'es'
    WHEN upper(country) IN ('FR','GF','MC') THEN 'fr'
    WHEN upper(country) IN ('DE','AT') THEN 'de'
    WHEN upper(country) = 'IT' THEN 'it'
    ELSE 'en' END
$$;

UPDATE supported_countries SET default_locale = platform_language(country_code);

UPDATE markets SET default_locale = 'pt', locales = 'pt' WHERE code = 'BR';
UPDATE markets SET default_locale = 'es', locales = 'es' WHERE code = 'LATAM';
UPDATE markets SET default_locale = 'en', locales = 'en' WHERE code IN ('US','CA','NA');
UPDATE markets SET default_locale = 'en', locales = 'en,es,pt,fr,de,it' WHERE code = 'EU';

-- The store's messages follow its billing country.
UPDATE organizations SET locale = platform_language(billing_country_code);

-- People keep the language they chose when it is one of the six (pt-BR → pt); anything else becomes English.
UPDATE users SET locale = CASE WHEN lower(split_part(replace(locale, '_', '-'), '-', 1)) IN ('pt','en','es','fr','de','it')
                               THEN lower(split_part(replace(locale, '_', '-'), '-', 1)) ELSE 'en' END;
UPDATE contacts SET locale = lower(split_part(replace(locale, '_', '-'), '-', 1))
 WHERE locale IS NOT NULL AND lower(split_part(replace(locale, '_', '-'), '-', 1)) IN ('pt','en','es','fr','de','it');
