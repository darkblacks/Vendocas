package com.platform.identity;

import com.platform.audit.AuditService;
import com.platform.identity.AuthDtos.*;
import com.platform.notifications.PlatformEmails;
import com.platform.security.JwtService;
import com.platform.shared.AppProperties;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import com.platform.shared.crypto.SecretCipher;
import com.platform.shared.crypto.Tokens;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
public class AuthService {
    static final int MAX_FAILED_LOGINS = 8;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final String DUMMY_HASH;
    static {
        DUMMY_HASH = org.springframework.security.crypto.argon2.Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("timing-equalizer");
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final PlatformEmails emails;
    private final AppProperties props;
    private final SecretCipher cipher;
    private final AuditService audit;
    private final Clock clock;

    public AuthService(UserRepository users, PasswordEncoder encoder, JdbcTemplate jdbc, JwtService jwt, PlatformEmails emails,
                       AppProperties props, SecretCipher cipher, AuditService audit, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.emails = emails;
        this.props = props;
        this.cipher = cipher;
        this.audit = audit;
        this.clock = clock;
    }

    /** Always succeeds from the caller's perspective to prevent account enumeration. */
    @Transactional
    public void register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase();
        validatePassword(req.password(), email);
        var existing = users.findByEmailIgnoreCase(email);
        if (existing.isPresent()) {
            emails.accountExists(email, existing.get().getLocale());
            return;
        }
        User u = new User();
        u.setId(Ids.newId());
        u.setEmail(email);
        u.setFullName(req.fullName().trim());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setLocale(req.locale() == null || req.locale().isBlank() ? "en" : req.locale());
        u.setTimeZone(validZone(req.timeZone()));
        u.setCreatedAt(now());
        u.setUpdatedAt(now());
        users.save(u);
        emails.verifyEmail(email, u.getLocale(), createToken(u.getId(), "EMAIL_VERIFICATION", Duration.ofHours(48)));
        audit.record(null, u.getId(), "USER", "user.registered", "user", u.getId(), Map.of(), null);
    }

    @Transactional
    public void resendVerification(String email) {
        users.findByEmailIgnoreCase(email.trim()).filter(u -> u.getEmailVerifiedAt() == null)
                .ifPresent(u -> emails.verifyEmail(u.getEmail(), u.getLocale(), createToken(u.getId(), "EMAIL_VERIFICATION", Duration.ofHours(48))));
    }

    @Transactional
    public void verifyEmail(String token) {
        UUID userId = consumeToken(token, "EMAIL_VERIFICATION");
        User u = users.findById(userId).orElseThrow(() -> ApiException.badRequest("Invalid token"));
        if (u.getEmailVerifiedAt() == null) {
            u.setEmailVerifiedAt(now());
            u.setUpdatedAt(now());
        }
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResponse login(LoginRequest req, String ip, String userAgent, TokenSink sink) {
        var maybe = users.findByEmailIgnoreCase(req.email().trim());
        if (maybe.isEmpty()) {
            encoder.matches(req.password(), DUMMY_HASH); // equalize timing
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }
        User u = maybe.get();
        if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(now())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }
        if (u.getPasswordHash() == null || !encoder.matches(req.password(), u.getPasswordHash())) {
            u.setFailedLogins(u.getFailedLogins() + 1);
            if (u.getFailedLogins() >= MAX_FAILED_LOGINS) {
                u.setLockedUntil(now().plus(LOCK_DURATION));
                u.setFailedLogins(0);
                audit.record(null, u.getId(), "USER", "user.locked", "user", u.getId(), Map.of(), ip);
            }
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }
        if (u.getEmailVerifiedAt() == null) throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED, "Confirm your email before signing in");
        u.setFailedLogins(0);
        if (u.mfaEnabled()) {
            return new LoginResponse(null, 0, true, createToken(u.getId(), "MFA_CHALLENGE", Duration.ofMinutes(5)));
        }
        return issue(u, false, ip, userAgent, sink, null);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResponse loginMfa(MfaLoginRequest req, String ip, String userAgent, TokenSink sink) {
        UUID userId = peekToken(req.mfaToken(), "MFA_CHALLENGE");
        User u = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid code"));
        if (!Totp.verify(cipher.decrypt(u.getMfaSecretEnc()), req.code(), now())) {
            u.setFailedLogins(u.getFailedLogins() + 1);
            if (u.getFailedLogins() >= MAX_FAILED_LOGINS) u.setLockedUntil(now().plus(LOCK_DURATION));
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid code");
        }
        consumeToken(req.mfaToken(), "MFA_CHALLENGE");
        u.setFailedLogins(0);
        return issue(u, true, ip, userAgent, sink, null);
    }

    /** Refresh-token rotation with reuse detection: presenting a revoked token revokes the whole family. */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginResponse refresh(String rawToken, String ip, String userAgent, TokenSink sink) {
        if (rawToken == null) throw new ApiException(ErrorCode.UNAUTHENTICATED, "No session");
        var rows = jdbc.queryForList("SELECT id, user_id, family_id, expires_at, revoked_at, mfa FROM refresh_tokens WHERE token_hash = ?",
                Tokens.sha256Hex(rawToken));
        if (rows.isEmpty()) throw new ApiException(ErrorCode.UNAUTHENTICATED, "Session expired");
        var row = rows.getFirst();
        UUID familyId = (UUID) row.get("family_id");
        if (row.get("revoked_at") != null) {
            jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE family_id = ? AND revoked_at IS NULL", familyId);
            audit.record(null, (UUID) row.get("user_id"), "USER", "session.refresh_reuse_detected", "user", row.get("user_id"), Map.of(), ip);
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Session expired");
        }
        if (((Timestamp) row.get("expires_at")).toInstant().isBefore(now())) throw new ApiException(ErrorCode.UNAUTHENTICATED, "Session expired");
        User u = users.findById((UUID) row.get("user_id")).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Session expired"));
        boolean mfa = Boolean.TRUE.equals(row.get("mfa"));
        LoginResponse resp = issue(u, mfa, ip, userAgent, sink, familyId);
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE id = ?", row.get("id"));
        return resp;
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null) return;
        jdbc.update("""
                UPDATE refresh_tokens SET revoked_at = now() WHERE revoked_at IS NULL AND family_id =
                  (SELECT family_id FROM refresh_tokens WHERE token_hash = ?)""", Tokens.sha256Hex(rawToken));
    }

    @Transactional
    public void forgotPassword(String email) {
        users.findByEmailIgnoreCase(email.trim()).ifPresent(u ->
                emails.resetPassword(u.getEmail(), u.getLocale(), createToken(u.getId(), "PASSWORD_RESET", Duration.ofMinutes(30))));
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest req) {
        UUID userId = consumeToken(req.token(), "PASSWORD_RESET");
        User u = users.findById(userId).orElseThrow(() -> ApiException.badRequest("Invalid token"));
        validatePassword(req.password(), u.getEmail());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setLockedUntil(null);
        u.setFailedLogins(0);
        if (u.getEmailVerifiedAt() == null) u.setEmailVerifiedAt(now()); // reset link proves mailbox control
        u.setUpdatedAt(now());
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", userId);
        audit.record(null, userId, "USER", "user.password_reset", "user", userId, Map.of(), null);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest req) {
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (!encoder.matches(req.currentPassword(), u.getPasswordHash())) throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Current password is wrong");
        validatePassword(req.newPassword(), u.getEmail());
        u.setPasswordHash(encoder.encode(req.newPassword()));
        u.setUpdatedAt(now());
        audit.record(null, userId, "USER", "user.password_changed", "user", userId, Map.of(), null);
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        User u = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Unknown user"));
        List<MembershipView> memberships = jdbc.query("""
                SELECT o.id, o.trade_name, m.role, o.country_code FROM organization_members m
                JOIN organizations o ON o.id = m.organization_id
                WHERE m.user_id = ? AND m.status = 'ACTIVE' AND o.status <> 'CLOSED' ORDER BY o.created_at""",
                (rs, i) -> new MembershipView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)), userId);
        return new MeResponse(u.getId(), u.getEmail(), u.getFullName(), u.getLocale(), u.getTimeZone(), u.getEmailVerifiedAt() != null,
                u.mfaEnabled(), u.getPlatformRole(), memberships);
    }

    // ---- MFA enrolment ----
    @Transactional
    public MfaSetupResponse beginMfaSetup(UUID userId) {
        User u = users.findById(userId).orElseThrow();
        if (u.mfaEnabled()) throw ApiException.invalidState("MFA already enabled");
        String secret = Totp.newSecret();
        u.setMfaSecretEnc(cipher.encrypt(secret));
        return new MfaSetupResponse(secret, Totp.otpauthUri("Vendocas", u.getEmail(), secret));
    }

    @Transactional
    public void enableMfa(UUID userId, String code) {
        User u = users.findById(userId).orElseThrow();
        if (u.getMfaSecretEnc() == null) throw ApiException.invalidState("Start MFA setup first");
        if (!Totp.verify(cipher.decrypt(u.getMfaSecretEnc()), code, now())) throw ApiException.badRequest("Invalid code");
        u.setMfaEnabledAt(now());
        audit.record(null, userId, "USER", "user.mfa_enabled", "user", userId, Map.of(), null);
    }

    @Transactional
    public void disableMfa(UUID userId, String code) {
        User u = users.findById(userId).orElseThrow();
        if (!u.mfaEnabled() || !Totp.verify(cipher.decrypt(u.getMfaSecretEnc()), code, now())) throw ApiException.badRequest("Invalid code");
        u.setMfaEnabledAt(null);
        u.setMfaSecretEnc(null);
        audit.record(null, userId, "USER", "user.mfa_disabled", "user", userId, Map.of(), null);
    }

    // ---- helpers ----
    public interface TokenSink { void refreshToken(String raw, Duration ttl); }

    private LoginResponse issue(User u, boolean mfa, String ip, String userAgent, TokenSink sink, UUID familyId) {
        u.setLastLoginAt(now());
        String raw = Tokens.random(32);
        Duration ttl = props.security().refreshTokenTtl();
        String ua = userAgent == null ? "" : userAgent;
        jdbc.update("INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, expires_at, user_agent, ip_address, mfa) VALUES (?,?,?,?,?,?,?,?)",
                Ids.newId(), u.getId(), familyId == null ? Ids.newId() : familyId, Tokens.sha256Hex(raw),
                Timestamp.from(now().plus(ttl)), ua.substring(0, Math.min(300, ua.length())), ip, mfa);
        sink.refreshToken(raw, ttl);
        String access = jwt.issueAccessToken(u.getId(), u.getEmail(), mfa, u.getPlatformRole());
        return new LoginResponse(access, props.security().accessTokenTtl().toSeconds(), false, null);
    }

    String createToken(UUID userId, String purpose, Duration ttl) {
        String raw = Tokens.random(32);
        jdbc.update("INSERT INTO user_tokens (id, user_id, purpose, token_hash, expires_at) VALUES (?,?,?,?,?)",
                Ids.newId(), userId, purpose, Tokens.sha256Hex(raw), Timestamp.from(now().plus(ttl)));
        return raw;
    }

    private UUID peekToken(String raw, String purpose) {
        var ids = jdbc.queryForList("SELECT user_id FROM user_tokens WHERE token_hash = ? AND purpose = ? AND used_at IS NULL AND expires_at > now()",
                UUID.class, Tokens.sha256Hex(raw), purpose);
        if (ids.isEmpty()) throw ApiException.badRequest("Invalid or expired token");
        return ids.getFirst();
    }

    private UUID consumeToken(String raw, String purpose) {
        var ids = jdbc.queryForList("""
                UPDATE user_tokens SET used_at = now() WHERE token_hash = ? AND purpose = ? AND used_at IS NULL AND expires_at > now()
                RETURNING user_id""", UUID.class, Tokens.sha256Hex(raw), purpose);
        if (ids.isEmpty()) throw ApiException.badRequest("Invalid or expired token");
        return ids.getFirst();
    }

    static void validatePassword(String password, String email) {
        if (password.length() < 10) throw ApiException.badRequest("Password must have at least 10 characters");
        if (password.equalsIgnoreCase(email)) throw ApiException.badRequest("Password must differ from the email");
        if (password.chars().distinct().count() < 5) throw ApiException.badRequest("Password is too simple");
    }

    private static String validZone(String tz) {
        if (tz == null || tz.isBlank()) return "UTC";
        try { return ZoneId.of(tz).getId(); } catch (Exception e) { throw ApiException.badRequest("Invalid time zone"); }
    }

    private Instant now() { return clock.instant(); }
}
