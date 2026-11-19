package com.platform.security;

import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/** Platform (operator) back-office: platform role from the DB (not only the token) and MFA always required. */
@Component
public class PlatformAdminInterceptor implements HandlerInterceptor {
    private final JdbcTemplate jdbc;

    public PlatformAdminInterceptor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        List<String> roles = jdbc.queryForList("SELECT platform_role FROM users WHERE id = ? AND mfa_enabled_at IS NOT NULL",
                String.class, CurrentUser.id());
        String role = roles.isEmpty() ? null : roles.getFirst();
        if (role == null) throw new ApiException(ErrorCode.FORBIDDEN, "Platform administrators only (MFA required)");
        if (!CurrentUser.mfaVerified()) throw new ApiException(ErrorCode.MFA_REQUIRED, "Second factor required");
        boolean mutating = !"GET".equals(request.getMethod());
        if (mutating && !"PLATFORM_ADMIN".equals(role)) throw new ApiException(ErrorCode.FORBIDDEN, "Read-only platform role");
        return true;
    }
}
