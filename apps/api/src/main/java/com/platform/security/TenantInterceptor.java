package com.platform.security;

import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import com.platform.tenants.Permission;
import com.platform.tenants.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Resolves and authorizes the tenant for methods annotated with {@link RequiresPermission}.
 * Cross-tenant access is impossible through these endpoints: membership is checked against the
 * database on every request and the resolved organization id is the only one services accept.
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {
    public static final String HEADER = "X-Organization-Id";
    /** New organizations get a grace window to enrol MFA before sensitive actions are gated. */
    static final long MFA_ENROLMENT_GRACE_DAYS = 14;
    private final JdbcTemplate jdbc;

    public TenantInterceptor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) return true;
        RequiresPermission rp = hm.getMethodAnnotation(RequiresPermission.class);
        if (rp == null) return true;
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) throw new ApiException(ErrorCode.BAD_REQUEST, "Missing " + HEADER + " header");
        UUID orgId;
        try { orgId = UUID.fromString(header); } catch (IllegalArgumentException e) { throw ApiException.badRequest("Invalid organization id"); }
        UUID userId = CurrentUser.id();

        List<MemberRow> rows = jdbc.query("""
                SELECT m.role, o.status, s.require_mfa_for_admins, o.created_at, u.mfa_enabled_at IS NOT NULL AS mfa_enabled
                FROM organization_members m
                JOIN organizations o ON o.id = m.organization_id
                JOIN users u ON u.id = m.user_id
                LEFT JOIN organization_settings s ON s.organization_id = o.id
                WHERE m.organization_id = ? AND m.user_id = ? AND m.status = 'ACTIVE'
                """, (rs, i) -> new MemberRow(Role.valueOf(rs.getString("role")), rs.getString("status"),
                rs.getBoolean("require_mfa_for_admins"), rs.getTimestamp("created_at").toInstant(), rs.getBoolean("mfa_enabled")), orgId, userId);
        // Same response for "not a member" and "does not exist" to avoid organization enumeration.
        if (rows.isEmpty()) throw new ApiException(ErrorCode.FORBIDDEN, "Not a member of this organization");
        MemberRow m = rows.getFirst();
        if ("CLOSED".equals(m.orgStatus)) throw new ApiException(ErrorCode.FORBIDDEN, "Organization is closed");
        Permission p = rp.value();
        if (!m.role.has(p)) throw new ApiException(ErrorCode.FORBIDDEN, "Your role does not allow " + p.name());
        if ("SUSPENDED_ADMIN".equals(m.orgStatus) && !p.name().endsWith("_READ") && p != Permission.BILLING_MANAGE) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Organization suspended by platform administration");
        }
        if (p.sensitive() && m.role.isAdministrative()) {
            if (m.mfaEnabled && !CurrentUser.mfaVerified()) {
                throw new ApiException(ErrorCode.MFA_REQUIRED, "Sign in again with your second factor");
            }
            boolean graceOver = m.orgCreatedAt.plus(MFA_ENROLMENT_GRACE_DAYS, ChronoUnit.DAYS).isBefore(Instant.now());
            if (m.requireMfa && !m.mfaEnabled && graceOver) {
                throw new ApiException(ErrorCode.MFA_REQUIRED, "Enable two-factor authentication to perform this action");
            }
        }
        TenantContext.set(new Tenant(orgId, userId, m.role));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }

    private record MemberRow(Role role, String orgStatus, boolean requireMfa, Instant orgCreatedAt, boolean mfaEnabled) {}
}
