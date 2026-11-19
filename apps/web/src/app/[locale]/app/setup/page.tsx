"use client";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useLocale, useTranslations } from "next-intl";
import { useRouter } from "@/i18n/navigation";
import { api, ApiError, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { Alert, Button, Card, Field, Input, PageHeader, Select } from "@/components/ui";

const EU = ["AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE"];
const COUNTRIES = ["BR", "US", ...EU];

type Form = { legalName: string; tradeName: string; countryCode: string; timeZone: string; contactEmail: string; financeEmail?: string; contactPhone?: string;
  addressLine1?: string; city?: string; region?: string; postalCode?: string; taxType: string; taxValue?: string; customerType: string; usState?: string; acceptTerms: boolean };

export default function SetupPage() {
  const t = useTranslations();
  const locale = useLocale();
  const router = useRouter();
  const { me, reload, setOrg } = useAuth();
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const regionName = new Intl.DisplayNames([locale], { type: "region" });
  const form = useForm<Form>({ defaultValues: { countryCode: "BR", timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone, contactEmail: me?.email ?? "", taxType: "BR_CNPJ", customerType: "BUSINESS", acceptTerms: false } });
  const country = form.watch("countryCode");
  const region = country === "BR" ? "BR" : country === "US" ? "US" : "EU";
  const taxTypes = region === "BR" ? ["BR_CNPJ", "BR_CPF"] : region === "US" ? ["US_EIN", "NONE"] : ["EU_VAT", "NONE"];

  const onSubmit = form.handleSubmit(async (v) => {
    setError(null); setFieldErrors({});
    try {
      const org = await api<{ id: string }>("/organizations", { tenant: false, body: {
        legalName: v.legalName, tradeName: v.tradeName, countryCode: v.countryCode, locale, timeZone: v.timeZone, contactEmail: v.contactEmail,
        financeEmail: v.financeEmail || null, contactPhone: v.contactPhone || null, addressLine1: v.addressLine1, city: v.city, region: v.region, postalCode: v.postalCode,
        taxId: { type: taxTypes.includes(v.taxType) ? v.taxType : taxTypes[0], value: v.taxValue || null, customerType: v.customerType, usState: v.usState || null },
        acceptTerms: v.acceptTerms } });
      await reload();
      setOrg(org.id);
      router.replace("/app/billing?welcome=1");
    } catch (e) {
      if (e instanceof ApiError) setFieldErrors(e.fields);
      setError(errorMessage(e));
    }
  });

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader title={t("setup.title")} description={t("setup.subtitle")} />
      <Card>
        <form onSubmit={onSubmit} className="grid gap-4 sm:grid-cols-2">
          <Field label={t("setup.legalName")} htmlFor="legalName"><Input id="legalName" required {...form.register("legalName")} /></Field>
          <Field label={t("setup.tradeName")} htmlFor="tradeName"><Input id="tradeName" required {...form.register("tradeName")} /></Field>
          <Field label={t("common.country")} htmlFor="country">
            <Select id="country" {...form.register("countryCode")}>
              {COUNTRIES.map((c) => <option key={c} value={c}>{regionName.of(c)}</option>)}
            </Select>
          </Field>
          <Field label={t("common.timeZone")} htmlFor="tz"><Input id="tz" required {...form.register("timeZone")} /></Field>
          <Field label={t("setup.taxIdType")} htmlFor="taxType" hint={t(region === "BR" ? "setup.taxHelpBR" : region === "US" ? "setup.taxHelpUS" : "setup.taxHelpEU")}>
            <Select id="taxType" {...form.register("taxType")}>{taxTypes.map((x) => <option key={x} value={x}>{x.replace("_", " ")}</option>)}</Select>
          </Field>
          <Field label={t("setup.taxId")} htmlFor="taxValue" error={fieldErrors["taxId.value"] ? "invalid" : undefined}><Input id="taxValue" {...form.register("taxValue")} /></Field>
          <Field label={t("setup.customerType")} htmlFor="ct">
            <Select id="ct" {...form.register("customerType")}><option value="BUSINESS">{t("setup.business")}</option><option value="INDIVIDUAL">{t("setup.individual")}</option></Select>
          </Field>
          {region === "US" && <Field label="State (US)" htmlFor="usState"><Input id="usState" maxLength={2} {...form.register("usState")} /></Field>}
          <Field label={t("setup.contactEmail")} htmlFor="ce"><Input id="ce" type="email" required {...form.register("contactEmail")} /></Field>
          <Field label={t("setup.financeEmail")} htmlFor="fe"><Input id="fe" type="email" {...form.register("financeEmail")} /></Field>
          <Field label={t("common.phone")} htmlFor="ph"><Input id="ph" type="tel" {...form.register("contactPhone")} /></Field>
          <Field label={t("setup.address")} htmlFor="a1"><Input id="a1" {...form.register("addressLine1")} /></Field>
          <Field label={t("setup.city")} htmlFor="city"><Input id="city" {...form.register("city")} /></Field>
          <Field label={t("setup.region")} htmlFor="region"><Input id="region" {...form.register("region")} /></Field>
          <Field label={t("setup.postalCode")} htmlFor="pc"><Input id="pc" {...form.register("postalCode")} /></Field>
          <label className="flex items-start gap-2 text-sm sm:col-span-2"><input type="checkbox" className="mt-1" {...form.register("acceptTerms")} />{t("setup.terms")}</label>
          {error && <div className="sm:col-span-2"><Alert tone="danger" title={error} /></div>}
          <div className="sm:col-span-2"><Button type="submit" loading={form.formState.isSubmitting}>{t("common.continue")}</Button></div>
        </form>
      </Card>
    </div>
  );
}
