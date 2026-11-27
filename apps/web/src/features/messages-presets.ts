/**
 * The two ready-made messages of "Minhas mensagens". Each preset is a regular automation tagged in its description, so it
 * runs on the same engine (consent, quiet hours, quotas, alert footer) and shows up in the reports.
 */
export const PRESET_INACTIVE = "preset:inactive-7d";
export const PRESET_MISSED_CALL = "preset:missed-call";

export type PresetKey = typeof PRESET_INACTIVE | typeof PRESET_MISSED_CALL;
export type Channel = "EMAIL" | "SMS" | "WHATSAPP";

export interface PresetConfig { days: number; channel: Channel; subject: string; body: string }

/** Automation definition for a preset (shape of the API's AutomationDefinition). */
export function definitionFor(key: PresetKey, c: PresetConfig) {
  const purpose = key === PRESET_INACTIVE ? "REACTIVATION" : "CUSTOMER_CARE";
  const send = { id: "send", type: "SEND", channel: c.channel, purpose, subject: c.channel === "EMAIL" ? c.subject : null, body: c.body };
  if (key === PRESET_INACTIVE) {
    return {
      trigger: { type: "CONTACT_INACTIVE", period: { amount: c.days, unit: "DAYS" }, basis: "LAST_PURCHASE", reentry: "ONCE_PER_EVENT" },
      steps: [
        { id: "check", type: "CONDITION", conditions: [{ type: "CONSENT", channel: c.channel === "WHATSAPP" ? "WHATSAPP" : c.channel, purpose }],
          match: "ALL", onTrue: "send", onFalse: "end" },
        send,
      ],
      settings: { exitOn: ["PURCHASE_COMPLETED"] },
    };
  }
  return {
    trigger: { type: "MISSED_CALL", reentry: "ONCE_PER_DAY" },
    steps: [send],
    settings: { exitOn: [] },
  };
}

/** Reads the editable values back from a saved definition. */
export function configFrom(def: unknown, fallback: PresetConfig): PresetConfig {
  const d = def as { trigger?: { period?: { amount?: number } }; steps?: { type: string; channel?: Channel; subject?: string; body?: string }[] } | null;
  const send = d?.steps?.find((s) => s.type === "SEND");
  return {
    days: d?.trigger?.period?.amount ?? fallback.days,
    channel: send?.channel ?? fallback.channel,
    subject: send?.subject ?? fallback.subject,
    body: send?.body ?? fallback.body,
  };
}
