package com.platform.contacts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Contact(UUID id, UUID organizationId, String externalId, String name, String email, String phoneE164, String locale,
                      String countryCode, String timeZone, LocalDate birthDate, Instant lastInteractionAt, Instant lastPurchaseAt,
                      Instant lastVisitAt, Instant nextAppointmentAt, Long lifetimeValueMinor, String lifetimeValueCurrency,
                      String status, String source, Map<String, Object> customFields, List<String> tags, Instant createdAt,
                      Instant updatedAt) {

    /** Template variables available for personalization (sensitive custom fields are filtered by the renderer). */
    public Map<String, Object> variables() {
        var m = new java.util.HashMap<String, Object>();
        m.put("contact.name", name == null ? "" : name);
        m.put("contact.first_name", name == null ? "" : name.trim().split("\\s+")[0]);
        m.put("contact.email", email == null ? "" : email);
        m.put("contact.phone", phoneE164 == null ? "" : phoneE164);
        if (customFields != null) customFields.forEach((k, v) -> m.put("contact.custom." + k, v == null ? "" : v));
        return m;
    }
}
