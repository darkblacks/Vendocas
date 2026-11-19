export type Unit = "MINUTES" | "HOURS" | "DAYS" | "WEEKS" | "CALENDAR_MONTHS" | "YEARS" | "BUSINESS_DAYS";
export interface Period { amount: number; unit: Unit }
export interface Audience { tagsAny?: string[]; tagsNone?: string[]; countries?: string[] }
export interface Trigger {
  type: string; eventType?: string; period?: Period; basis?: string; leadMinutes?: number; dateField?: string;
  schedule?: { recurrence: string; time: string; daysOfWeek?: number[]; dayOfMonth?: number }; audience?: Audience; reentry?: string;
}
export interface Condition { type: string; channel?: string; purpose?: string; tag?: string; days?: number; countries?: string[]; amountMinor?: number; eventType?: string; from?: string; to?: string }
export interface Step {
  id: string; type: string; duration?: Period; time?: string; businessDaysOnly?: boolean; zone?: string;
  conditions?: Condition[]; match?: string; onTrue?: string; onFalse?: string;
  channel?: string; purpose?: string; templateId?: string; subject?: string; body?: string; mediaAssetId?: string;
  tag?: string; title?: string; dueInHours?: number; message?: string; status?: string; url?: string; valueMinor?: number;
  next?: string; ui?: { x: number; y: number };
}
export interface Settings { exitOn?: string[]; lateToleranceHours?: number; onSendBlocked?: string; monthlyBudgetMinor?: number }
export interface Definition { trigger: Trigger; steps: Step[]; settings?: Settings }

export const TRIGGERS = ["CONTACT_INACTIVE", "PURCHASE_COMPLETED", "CHECKOUT_STARTED", "CHECKOUT_ABANDONED", "MISSED_CALL", "APPOINTMENT_UPCOMING",
  "APPOINTMENT_CREATED", "APPOINTMENT_CANCELED", "SERVICE_COMPLETED", "QUOTE_CREATED", "CONTACT_CREATED", "FORM_SUBMITTED", "REPLY_RECEIVED",
  "BIRTHDAY", "DATE_FIELD", "RECURRING", "EXTERNAL_EVENT"];
export const UNITS: Unit[] = ["MINUTES", "HOURS", "DAYS", "WEEKS", "CALENDAR_MONTHS", "YEARS", "BUSINESS_DAYS"];
export const STEP_TYPES = ["WAIT", "WAIT_UNTIL", "CONDITION", "SEND", "ADD_TAG", "REMOVE_TAG", "CREATE_TASK", "CREATE_OPPORTUNITY", "NOTIFY_OPERATOR", "SET_CONTACT_STATUS", "CALL_WEBHOOK", "END"];
export const CONDITIONS = ["CONSENT", "NO_PURCHASE_SINCE_TRIGGER", "NO_REPLY_SINCE_TRIGGER", "NOT_MESSAGED_RECENTLY", "HAS_EMAIL", "HAS_PHONE", "CHANNEL_CONNECTED",
  "CHANNEL_IN_PLAN", "NOT_BLOCKED", "HAS_TAG", "NOT_HAS_TAG", "COUNTRY_IN", "LIFETIME_VALUE_GTE", "HAS_EVENT", "WITHIN_HOURS", "BUDGET_AVAILABLE"];
export const EXIT_EVENTS = ["PURCHASE_COMPLETED", "REPLY_RECEIVED", "OPTED_OUT", "APPOINTMENT_CANCELED", "CALL_ANSWERED"];

let seq = 0;
export function newStep(type: string): Step {
  const id = `${type.toLowerCase().replace(/_/g, "")}${Date.now().toString(36)}${seq++}`;
  switch (type) {
    case "WAIT": return { id, type, duration: { amount: 1, unit: "DAYS" } };
    case "WAIT_UNTIL": return { id, type, time: "10:00", zone: "CONTACT", businessDaysOnly: false };
    case "CONDITION": return { id, type, conditions: [{ type: "CONSENT", channel: "EMAIL", purpose: "MARKETING" }], match: "ALL" };
    case "SEND": return { id, type, channel: "EMAIL", purpose: "REACTIVATION", subject: "", body: "" };
    case "ADD_TAG": case "REMOVE_TAG": return { id, type, tag: "" };
    case "CREATE_TASK": return { id, type, title: "Call back", dueInHours: 24 };
    case "CREATE_OPPORTUNITY": return { id, type, title: "Follow up" };
    case "NOTIFY_OPERATOR": return { id, type, title: "Automation", message: "" };
    case "SET_CONTACT_STATUS": return { id, type, status: "BLOCKED" };
    case "CALL_WEBHOOK": return { id, type, url: "https://" };
    default: return { id, type };
  }
}

/** In simple (linear) mode a CONDITION continues to the following step when true and ends when false. */
export function linearize(steps: Step[]): Step[] {
  return steps.map((s, i) => {
    const following = steps[i + 1]?.id;
    if (s.type === "CONDITION") return { ...s, onTrue: following ?? "end", onFalse: "end", next: undefined };
    return { ...s, next: undefined };
  });
}

export function defaultDefinition(): Definition {
  return {
    trigger: { type: "CONTACT_INACTIVE", period: { amount: 3, unit: "CALENDAR_MONTHS" }, basis: "LAST_PURCHASE" },
    steps: [
      { id: "check", type: "CONDITION", conditions: [{ type: "CONSENT", channel: "EMAIL", purpose: "REACTIVATION" }], match: "ALL", onTrue: "send", onFalse: "end" },
      { id: "send", type: "SEND", channel: "EMAIL", purpose: "REACTIVATION", subject: "We miss you, {{contact.first_name}}", body: "It has been a while! Come back to {{business.name}} this week." },
    ],
    settings: { exitOn: ["PURCHASE_COMPLETED"], lateToleranceHours: 24 },
  };
}
