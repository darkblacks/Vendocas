export interface Contact {
  id: string; externalId?: string; name?: string; email?: string; phoneE164?: string; locale?: string; countryCode?: string; timeZone?: string;
  lastPurchaseAt?: string; lastInteractionAt?: string; lastVisitAt?: string; nextAppointmentAt?: string; lifetimeValueMinor?: number;
  lifetimeValueCurrency?: string; status: string; source: string; tags: string[]; customFields: Record<string, unknown>; createdAt: string;
}
export interface Page<T> { items: T[]; page: number; size: number; totalItems: number; totalPages: number }
export const CHANNELS = ["EMAIL", "WHATSAPP", "SMS"] as const;
export const PURPOSES = ["TRANSACTIONAL", "SERVICE_REMINDER", "CUSTOMER_CARE", "MARKETING", "REACTIVATION"] as const;
export const BASES = ["CONSENT", "CONTRACT", "LEGITIMATE_INTEREST", "EXISTING_CUSTOMER_SOFT_OPT_IN", "LEGAL_OBLIGATION"] as const;
