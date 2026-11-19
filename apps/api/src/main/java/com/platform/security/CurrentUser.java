package com.platform.security;

import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

public final class CurrentUser {
    private CurrentUser() {}

    public static Jwt jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) return jwt;
        throw new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication required");
    }

    public static UUID id() { return UUID.fromString(jwt().getSubject()); }

    public static boolean mfaVerified() {
        List<String> amr = jwt().getClaimAsStringList("amr");
        return amr != null && amr.contains("mfa");
    }

    public static String platformRole() { return jwt().getClaimAsString("pr"); }
}
