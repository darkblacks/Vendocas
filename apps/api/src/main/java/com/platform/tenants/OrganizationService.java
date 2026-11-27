package com.platform.tenants;

import com.platform.audit.AuditService;
import com.platform.identity.User;
import com.platform.identity.UserRepository;
import com.platform.notifications.PlatformEmails;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import com.platform.shared.crypto.SecretCipher;
import com.platform.shared.crypto.Tokens;
import com.platform.tenants.TenantDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrganizationService {
    public static final String TERMS_VERSION = "2026-11";
    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final ViesClient vies;
    private final AuditService audit;
    private final PlatformEmails emails;
    private final Clock clock;

    public OrganizationService(OrganizationRepository orgs, UserRepository users, JdbcTemplate jdbc, SecretCipher cipher,
                               ViesClient vies, AuditService audit, PlatformEmails emails, Clock clock) {
        this.orgs = orgs;
        this.users = users;
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.vies = vies;
        this.audit = audit;
        this.emails = emails;
        this.clock = clock;
    }

    public record CountryInfo(String region, String defaultCurrency, String market) {}

    public CountryInfo country(String code) {
        var rows = jdbc.query("SELECT region, default_currency, market_code FROM supported_countries WHERE country_code = ?",
                (rs, i) -> new CountryInfo(rs.getString(1), rs.getString(2), rs.getString(3)), code);
        if (rows.isEmpty()) throw new ApiException(ErrorCode.UNSUPPORTED_IN_COUNTRY, "Country not supported: " + code);
        return rows.getFirst();
    }

    @Transactional
    public Organization create(UUID ownerId, CreateOrganizationRequest req) {
        User owner = users.findById(ownerId).orElseThrow();
        if (owner.getEmailVerifiedAt() == null) throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED, "Verify your email first");
        String billingCountry = req.billingCountryCode() == null ? req.countryCode() : req.billingCountryCode();
        CountryInfo incorporation = country(req.countryCode());
        CountryInfo billing = country(billingCountry);
        validateZone(req.timeZone());
        Boolean marketOpen = jdbc.queryForObject("SELECT enabled FROM markets WHERE code = ?", Boolean.class, billing.market());
        if (!Boolean.TRUE.equals(marketOpen)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_IN_COUNTRY, "Vendocas is not open in this market yet", Map.of("market", billing.market()));
        }
        TaxIdInput tax = req.taxId();
        if (!TaxIdValidator.typeAllowedFor(billing.region(), tax.type())) {
            throw ApiException.badRequest("Tax identifier type " + tax.type() + " is not valid for " + billingCountry);
        }
        String normalized = TaxIdValidator.normalize(tax.value());
        if (!"NONE".equals(tax.type()) && !"OTHER".equals(tax.type()) && !TaxIdValidator.isValid(tax.type(), billingCountry, normalized)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid tax identifier", Map.of("fields", Map.of("taxId.value", "invalid")));
        }

        Instant now = clock.instant();
        Organization o = new Organization();
        o.setId(Ids.newId());
        o.setLegalName(req.legalName().trim());
        o.setTradeName(req.tradeName().trim());
        o.setCountryCode(req.countryCode());
        o.setBillingCountryCode(billingCountry);
        o.setBillingCurrency(billing.defaultCurrency());
        o.setMarketCode(billing.market());
        o.setLocale(req.locale());
        o.setTimeZone(req.timeZone());
        o.setDataRegion(switch (incorporation.region()) { case "EU", "UK" -> "eu"; case "BR" -> "br"; default -> "us"; });
        o.setContactEmail(req.contactEmail());
        o.setFinanceEmail(req.financeEmail());
        o.setContactPhone(req.contactPhone());
        o.setAddressLine1(req.addressLine1());
        o.setAddressLine2(req.addressLine2());
        o.setCity(req.city());
        o.setRegion(req.region());
        o.setPostalCode(req.postalCode());
        o.setStatus("ACTIVE");
        o.setTermsAcceptedAt(now);
        o.setTermsVersion(TERMS_VERSION);
        o.setPublicKey("pk_" + Tokens.random(18));
        o.setTrackingSecretEnc(cipher.encrypt("sk_" + Tokens.random(32)));
        o.setCreatedAt(now);
        o.setUpdatedAt(now);
        orgs.save(o);
        orgs.flush();

        jdbc.update("INSERT INTO organization_members (id, organization_id, user_id, role, status) VALUES (?,?,?,'OWNER','ACTIVE')",
                Ids.newId(), o.getId(), ownerId);
        jdbc.update("INSERT INTO organization_settings (organization_id) VALUES (?)", o.getId());
        jdbc.update("INSERT INTO organization_onboarding (organization_id) VALUES (?)", o.getId()); // mandatory onboarding starts
        String status = switch (tax.type()) { case "NONE", "OTHER" -> "UNVERIFIED"; default -> "FORMAT_VALID"; };
        jdbc.update("""
                INSERT INTO tax_profiles (id, organization_id, country_code, customer_type, tax_id_type, tax_id, tax_id_status, municipal_registration, us_state)
                VALUES (?,?,?,?,?,?,?,?,?)""", Ids.newId(), o.getId(), billingCountry,
                tax.customerType() == null ? "BUSINESS" : tax.customerType(), tax.type(), normalized, status,
                tax.municipalRegistration(), tax.usState());
        jdbc.update("INSERT INTO credit_wallets (organization_id, currency) VALUES (?, ?)", o.getId(), o.getBillingCurrency());
        audit.user(o.getId(), ownerId, "organization.created", "organization", o.getId(), Map.of("country", o.getCountryCode()));
        return o;
    }

    @Transactional
    public Organization update(UUID orgId, UUID userId, UpdateOrganizationRequest req) {
        Organization o = get(orgId);
        validateZone(req.timeZone());
        o.setLegalName(req.legalName());
        o.setTradeName(req.tradeName());
        o.setLocale(req.locale());
        o.setTimeZone(req.timeZone());
        o.setContactEmail(req.contactEmail());
        o.setFinanceEmail(req.financeEmail());
        o.setContactPhone(req.contactPhone());
        o.setAddressLine1(req.addressLine1());
        o.setAddressLine2(req.addressLine2());
        o.setCity(req.city());
        o.setRegion(req.region());
        o.setPostalCode(req.postalCode());
        o.setUpdatedAt(clock.instant());
        audit.user(orgId, userId, "organization.updated", "organization", orgId, Map.of());
        return o;
    }

    public Organization get(UUID orgId) { return orgs.findById(orgId).orElseThrow(() -> ApiException.notFound("Organization")); }

    public TaxProfileView taxProfile(UUID orgId) {
        return jdbc.queryForObject("""
                SELECT country_code, customer_type, tax_id_type, tax_id, tax_id_status, tax_id_verified_at, municipal_registration, us_state
                FROM tax_profiles WHERE organization_id = ?""", (rs, i) -> new TaxProfileView(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getString(7), rs.getString(8)), orgId);
    }

    @Transactional
    public TaxProfileView updateTaxId(UUID orgId, UUID userId, TaxIdInput input) {
        TaxProfileView current = taxProfile(orgId);
        CountryInfo c = country(current.countryCode());
        if (!TaxIdValidator.typeAllowedFor(c.region(), input.type())) throw ApiException.badRequest("Tax id type not allowed for country");
        String normalized = TaxIdValidator.normalize(input.value());
        boolean needsValue = !"NONE".equals(input.type()) && !"OTHER".equals(input.type());
        if (needsValue && !TaxIdValidator.isValid(input.type(), current.countryCode(), normalized)) throw ApiException.badRequest("Invalid tax identifier");
        jdbc.update("""
                UPDATE tax_profiles SET tax_id_type=?, tax_id=?, tax_id_status=?, tax_id_verified_at=NULL, verification_ref=NULL,
                  customer_type=COALESCE(?, customer_type), municipal_registration=?, us_state=?, updated_at=now() WHERE organization_id=?""",
                input.type(), normalized, needsValue ? "FORMAT_VALID" : "UNVERIFIED", input.customerType(),
                input.municipalRegistration(), input.usState(), orgId);
        audit.user(orgId, userId, "tax_profile.updated", "tax_profile", orgId, Map.of("type", input.type()));
        return taxProfile(orgId);
    }

    /** Registry verification — currently EU VAT via VIES. */
    @Transactional
    public TaxProfileView verifyTaxId(UUID orgId, UUID userId) {
        TaxProfileView p = taxProfile(orgId);
        if (!"EU_VAT".equals(p.taxIdType())) throw ApiException.badRequest("Registry verification is available for EU VAT numbers");
        ViesClient.Result r = vies.check(p.countryCode(), p.taxId());
        jdbc.update("UPDATE tax_profiles SET tax_id_status=?, tax_id_verified_at=?, verification_ref=?, updated_at=now() WHERE organization_id=?",
                r.status(), "VERIFIED".equals(r.status()) ? Timestamp.from(clock.instant()) : null, r.reference(), orgId);
        audit.user(orgId, userId, "tax_profile.verified", "tax_profile", orgId, Map.of("result", r.status()));
        return taxProfile(orgId);
    }

    public SettingsView settings(UUID orgId) {
        return jdbc.queryForObject("""
                SELECT s.quiet_hours_start, s.quiet_hours_end, s.default_frequency_cap, s.daily_spend_limit_minor, s.monthly_spend_limit_minor,
                       s.low_balance_threshold_minor, s.data_retention_days, s.require_mfa_for_admins, s.alert_contact, o.market_code
                FROM organization_settings s JOIN organizations o ON o.id = s.organization_id WHERE s.organization_id = ?""",
                (rs, i) -> new SettingsView(rs.getTime(1).toLocalTime(), rs.getTime(2).toLocalTime(), rs.getInt(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getInt(7), rs.getBoolean(8), rs.getString(9), rs.getString(10)), orgId);
    }

    @Transactional
    public SettingsView updateSettings(UUID orgId, UUID userId, UpdateSettingsRequest r) {
        jdbc.update("""
                UPDATE organization_settings SET quiet_hours_start=?, quiet_hours_end=?, default_frequency_cap=?, daily_spend_limit_minor=?,
                  monthly_spend_limit_minor=?, low_balance_threshold_minor=?, data_retention_days=?, require_mfa_for_admins=?, updated_at=now()
                WHERE organization_id=?""", r.quietHoursStart(), r.quietHoursEnd(), r.defaultFrequencyCap(), r.dailySpendLimitMinor(),
                r.monthlySpendLimitMinor(), r.lowBalanceThresholdMinor(), r.dataRetentionDays(), r.requireMfaForAdmins(), orgId);
        if (r.alertContact() != null) {
            jdbc.update("UPDATE organization_settings SET alert_contact = ? WHERE organization_id = ?",
                    r.alertContact().isBlank() ? null : r.alertContact().trim(), orgId);
        }
        audit.user(orgId, userId, "settings.updated", "organization", orgId, Map.of());
        return settings(orgId);
    }

    /** Rotates the tracking HMAC secret and returns it once. */
    @Transactional
    public TrackingKeysView rotateTrackingSecret(UUID orgId, UUID userId) {
        Organization o = get(orgId);
        String secret = "sk_" + Tokens.random(32);
        o.setTrackingSecretEnc(cipher.encrypt(secret));
        audit.user(orgId, userId, "tracking_secret.rotated", "organization", orgId, Map.of());
        return new TrackingKeysView(o.getPublicKey(), secret);
    }

    // ---- members ----
    public List<MemberView> members(UUID orgId) {
        return jdbc.query("""
                SELECT u.id, u.email, u.full_name, m.role, m.status, u.mfa_enabled_at IS NOT NULL FROM organization_members m
                JOIN users u ON u.id = m.user_id WHERE m.organization_id = ? ORDER BY m.created_at""",
                (rs, i) -> new MemberView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6)), orgId);
    }

    @Transactional
    public void invite(UUID orgId, UUID inviterId, InviteRequest req) {
        Organization o = get(orgId);
        String raw = Tokens.random(32);
        jdbc.update("DELETE FROM member_invitations WHERE organization_id=? AND email=? AND accepted_at IS NULL", orgId, req.email());
        jdbc.update("INSERT INTO member_invitations (id, organization_id, email, role, token_hash, expires_at, invited_by) VALUES (?,?,?,?,?,?,?)",
                Ids.newId(), orgId, req.email().toLowerCase(), req.role(), Tokens.sha256Hex(raw),
                Timestamp.from(clock.instant().plus(Duration.ofDays(7))), inviterId);
        emails.invitation(req.email(), o.getLocale(), o.getTradeName(), raw);
        audit.user(orgId, inviterId, "member.invited", "invitation", req.email(), Map.of("role", req.role()));
    }

    /** The invitation must be accepted by a signed-in user whose verified email matches it. */
    @Transactional
    public UUID acceptInvitation(UUID userId, String rawToken) {
        User u = users.findById(userId).orElseThrow();
        var rows = jdbc.queryForList("""
                SELECT id, organization_id, email, role FROM member_invitations
                WHERE token_hash = ? AND accepted_at IS NULL AND expires_at > now()""", Tokens.sha256Hex(rawToken));
        if (rows.isEmpty()) throw ApiException.badRequest("Invalid or expired invitation");
        var inv = rows.getFirst();
        if (u.getEmailVerifiedAt() == null || !u.getEmail().equalsIgnoreCase(String.valueOf(inv.get("email")))) {
            throw ApiException.forbidden("This invitation was sent to a different email address");
        }
        UUID orgId = (UUID) inv.get("organization_id");
        jdbc.update("""
                INSERT INTO organization_members (id, organization_id, user_id, role, status) VALUES (?,?,?,?,'ACTIVE')
                ON CONFLICT (organization_id, user_id) DO UPDATE SET role = EXCLUDED.role, status = 'ACTIVE', updated_at = now()""",
                Ids.newId(), orgId, userId, inv.get("role"));
        jdbc.update("UPDATE member_invitations SET accepted_at = now() WHERE id = ?", inv.get("id"));
        audit.user(orgId, userId, "member.joined", "user", userId, Map.of("role", String.valueOf(inv.get("role"))));
        return orgId;
    }

    @Transactional
    public void changeRole(UUID orgId, UUID actorId, Role actorRole, UUID targetUserId, Role newRole) {
        if (newRole == Role.OWNER && actorRole != Role.OWNER) throw ApiException.forbidden("Only owners can grant ownership");
        Role current = memberRole(orgId, targetUserId);
        if (current == Role.OWNER && actorRole != Role.OWNER) throw ApiException.forbidden("Only owners can change an owner");
        if (current == Role.OWNER && newRole != Role.OWNER) ensureAnotherOwner(orgId, targetUserId);
        jdbc.update("UPDATE organization_members SET role=?, updated_at=now() WHERE organization_id=? AND user_id=?", newRole.name(), orgId, targetUserId);
        audit.user(orgId, actorId, "member.role_changed", "user", targetUserId, Map.of("from", current.name(), "to", newRole.name()));
    }

    @Transactional
    public void removeMember(UUID orgId, UUID actorId, Role actorRole, UUID targetUserId) {
        Role current = memberRole(orgId, targetUserId);
        if (current == Role.OWNER) {
            if (actorRole != Role.OWNER) throw ApiException.forbidden("Only owners can remove an owner");
            ensureAnotherOwner(orgId, targetUserId);
        }
        jdbc.update("DELETE FROM organization_members WHERE organization_id=? AND user_id=?", orgId, targetUserId);
        audit.user(orgId, actorId, "member.removed", "user", targetUserId, Map.of());
    }

    private Role memberRole(UUID orgId, UUID userId) {
        var r = jdbc.queryForList("SELECT role FROM organization_members WHERE organization_id=? AND user_id=?", String.class, orgId, userId);
        if (r.isEmpty()) throw ApiException.notFound("Member");
        return Role.valueOf(r.getFirst());
    }

    private void ensureAnotherOwner(UUID orgId, UUID excluding) {
        Integer owners = jdbc.queryForObject("SELECT count(*) FROM organization_members WHERE organization_id=? AND role='OWNER' AND status='ACTIVE' AND user_id<>?",
                Integer.class, orgId, excluding);
        if (owners == null || owners == 0) throw ApiException.invalidState("An organization must keep at least one owner");
    }

    private static void validateZone(String tz) {
        try { ZoneId.of(tz); } catch (Exception e) { throw ApiException.badRequest("Invalid IANA time zone"); }
    }
}
