package com.platform.contacts;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ContactDtos {
    private ContactDtos() {}

    public record ContactInput(@Size(max = 120) String externalId, @Size(max = 250) String name, @Email @Size(max = 254) String email,
                               @Size(max = 30) String phone, @Size(max = 35) String locale, @Pattern(regexp = "[A-Z]{2}") String countryCode,
                               @Size(max = 64) String timeZone, LocalDate birthDate, Instant lastInteractionAt, Instant lastPurchaseAt,
                               Instant lastVisitAt, Instant nextAppointmentAt, @PositiveOrZero Long lifetimeValueMinor,
                               @Pattern(regexp = "[A-Z]{3}") String lifetimeValueCurrency, List<@Size(max = 80) String> tags,
                               Map<String, Object> customFields) {}

    public record CustomFieldInput(@NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{0,59}$") String key, @NotBlank @Size(max = 120) String label,
                                   @NotBlank @Pattern(regexp = "TEXT|NUMBER|DATE|DATETIME|BOOLEAN") String dataType, boolean sensitive) {}

    public record CustomFieldView(String key, String label, String dataType, boolean sensitive) {}

    public record TimelineItem(String kind, String type, String channel, String status, String summary, Instant occurredAt) {}
}
