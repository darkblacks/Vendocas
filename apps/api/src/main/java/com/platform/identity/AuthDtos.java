package com.platform.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(@NotBlank @Size(max = 200) String fullName,
                                  @NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Size(min = 10, max = 128) String password,
                                  @Size(max = 35) String locale,
                                  @Size(max = 64) String timeZone) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record MfaLoginRequest(@NotBlank String mfaToken, @NotBlank @Size(min = 6, max = 6) String code) {}

    public record TokenRequest(@NotBlank String token) {}

    public record EmailRequest(@NotBlank @Email String email) {}

    public record ResetPasswordRequest(@NotBlank String token, @NotBlank @Size(min = 10, max = 128) String password) {}

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 10, max = 128) String newPassword) {}

    /** Either tokens or an MFA challenge is returned — never both. */
    public record LoginResponse(String accessToken, long expiresIn, boolean mfaRequired, String mfaToken) {}

    public record MembershipView(UUID organizationId, String organizationName, String role, String countryCode) {}

    public record MeResponse(UUID id, String email, String fullName, String locale, String timeZone, boolean emailVerified,
                             boolean mfaEnabled, String platformRole, List<MembershipView> memberships) {}

    public record MfaSetupResponse(String secret, String otpauthUri) {}

    public record MfaCodeRequest(@NotBlank @Size(min = 6, max = 6) String code) {}
}
