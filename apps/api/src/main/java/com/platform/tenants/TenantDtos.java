package com.platform.tenants;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

public final class TenantDtos {
    private TenantDtos() {}

    public record CreateOrganizationRequest(
            @NotBlank @Size(max = 250) String legalName,
            @NotBlank @Size(max = 250) String tradeName,
            @NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode,
            @Pattern(regexp = "[A-Z]{2}") String billingCountryCode,
            @NotBlank @Size(max = 35) String locale,
            @NotBlank @Size(max = 64) String timeZone,
            @NotBlank @Email String contactEmail,
            @Email String financeEmail,
            @Size(max = 30) String contactPhone,
            @Size(max = 250) String addressLine1, @Size(max = 250) String addressLine2,
            @Size(max = 120) String city, @Size(max = 120) String region, @Size(max = 30) String postalCode,
            @NotNull TaxIdInput taxId,
            @AssertTrue(message = "Terms must be accepted") boolean acceptTerms) {}

    public record TaxIdInput(@NotBlank String type, @Size(max = 40) String value, @Pattern(regexp = "BUSINESS|INDIVIDUAL") String customerType,
                             @Size(max = 40) String municipalRegistration, @Size(max = 2) String usState) {}

    public record UpdateOrganizationRequest(
            @NotBlank @Size(max = 250) String legalName, @NotBlank @Size(max = 250) String tradeName,
            @NotBlank @Size(max = 35) String locale, @NotBlank @Size(max = 64) String timeZone,
            @NotBlank @Email String contactEmail, @Email String financeEmail, @Size(max = 30) String contactPhone,
            @Size(max = 250) String addressLine1, @Size(max = 250) String addressLine2,
            @Size(max = 120) String city, @Size(max = 120) String region, @Size(max = 30) String postalCode) {}

    public record OrganizationView(UUID id, String legalName, String tradeName, String countryCode, String billingCountryCode,
                                   String billingCurrency, String locale, String timeZone, String dataRegion, String contactEmail,
                                   String financeEmail, String contactPhone, String addressLine1, String addressLine2, String city,
                                   String region, String postalCode, String status, String publicKey, Instant createdAt) {
        static OrganizationView of(Organization o) {
            return new OrganizationView(o.getId(), o.getLegalName(), o.getTradeName(), o.getCountryCode(), o.getBillingCountryCode(),
                    o.getBillingCurrency(), o.getLocale(), o.getTimeZone(), o.getDataRegion(), o.getContactEmail(), o.getFinanceEmail(),
                    o.getContactPhone(), o.getAddressLine1(), o.getAddressLine2(), o.getCity(), o.getRegion(), o.getPostalCode(),
                    o.getStatus(), o.getPublicKey(), o.getCreatedAt());
        }
    }

    public record TaxProfileView(String countryCode, String customerType, String taxIdType, String taxId, String taxIdStatus,
                                 Instant verifiedAt, String municipalRegistration, String usState) {}

    public record SettingsView(LocalTime quietHoursStart, LocalTime quietHoursEnd, int defaultFrequencyCap,
                               long dailySpendLimitMinor, long monthlySpendLimitMinor, long lowBalanceThresholdMinor,
                               int dataRetentionDays, boolean requireMfaForAdmins, String alertContact, String marketCode) {}

    public record UpdateSettingsRequest(@NotNull LocalTime quietHoursStart, @NotNull LocalTime quietHoursEnd,
                                        @Min(1) @Max(50) int defaultFrequencyCap,
                                        @Min(0) long dailySpendLimitMinor, @Min(0) long monthlySpendLimitMinor,
                                        @Min(0) long lowBalanceThresholdMinor, @Min(30) @Max(3650) int dataRetentionDays,
                                        boolean requireMfaForAdmins,
                                        // Contact shown in the alert footer; null = keep, blank = use the organization's phone/e-mail.
                                        @jakarta.validation.constraints.Size(max = 200) String alertContact) {}

    public record MemberView(UUID userId, String email, String fullName, String role, String status, boolean mfaEnabled) {}

    public record InviteRequest(@NotBlank @Email String email, @NotBlank @Pattern(regexp = "ADMIN|FINANCE|OPERATOR|READ_ONLY") String role) {}

    public record ChangeRoleRequest(@NotBlank @Pattern(regexp = "OWNER|ADMIN|FINANCE|OPERATOR|READ_ONLY") String role) {}

    public record TrackingKeysView(String publicKey, String secret) {}
}
