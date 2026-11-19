package com.platform.identity;

import com.platform.identity.AuthDtos.*;
import com.platform.security.CurrentUser;
import com.platform.shared.AppProperties;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import com.platform.shared.web.ClientIp;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth")
public class AuthController {
    static final String REFRESH_COOKIE = "vd_rt";
    private final AuthService auth;
    private final AppProperties props;

    public AuthController(AuthService auth, AppProperties props) {
        this.auth = auth;
        this.props = props;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void register(@Valid @RequestBody RegisterRequest req) { auth.register(req); }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@Valid @RequestBody TokenRequest req) { auth.verifyEmail(req.token()); }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resend(@Valid @RequestBody EmailRequest req) { auth.resendVerification(req.email()); }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http, HttpServletResponse res) {
        return auth.login(req, ClientIp.of(http), http.getHeader(HttpHeaders.USER_AGENT), sink(res));
    }

    @PostMapping("/login/mfa")
    public LoginResponse loginMfa(@Valid @RequestBody MfaLoginRequest req, HttpServletRequest http, HttpServletResponse res) {
        return auth.loginMfa(req, ClientIp.of(http), http.getHeader(HttpHeaders.USER_AGENT), sink(res));
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String token,
                                 @RequestHeader(name = "X-Requested-With", required = false) String xrw,
                                 HttpServletRequest http, HttpServletResponse res) {
        requireXhr(xrw);
        return auth.refresh(token, ClientIp.of(http), http.getHeader(HttpHeaders.USER_AGENT), sink(res));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token,
                       @RequestHeader(name = "X-Requested-With", required = false) String xrw, HttpServletResponse res) {
        requireXhr(xrw);
        auth.logout(token);
        res.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgot(@Valid @RequestBody EmailRequest req) { auth.forgotPassword(req.email()); }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody ResetPasswordRequest req) { auth.resetPassword(req); }

    @PostMapping("/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(@Valid @RequestBody ChangePasswordRequest req) { auth.changePassword(CurrentUser.id(), req); }

    @GetMapping("/me")
    public MeResponse me() { return auth.me(CurrentUser.id()); }

    @PostMapping("/mfa/setup")
    public MfaSetupResponse mfaSetup() { return auth.beginMfaSetup(CurrentUser.id()); }

    @PostMapping("/mfa/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mfaEnable(@Valid @RequestBody MfaCodeRequest req) { auth.enableMfa(CurrentUser.id(), req.code()); }

    @PostMapping("/mfa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mfaDisable(@Valid @RequestBody MfaCodeRequest req) { auth.disableMfa(CurrentUser.id(), req.code()); }

    private AuthService.TokenSink sink(HttpServletResponse res) {
        return (raw, ttl) -> res.addHeader(HttpHeaders.SET_COOKIE, cookie(raw, ttl).toString());
    }

    private ResponseCookie cookie(String value, Duration ttl) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true).secure(props.security().secureCookies()).sameSite("Strict").path("/api/v1/auth").maxAge(ttl);
        if (props.security().cookieDomain() != null && !props.security().cookieDomain().isBlank()) b.domain(props.security().cookieDomain());
        return b.build();
    }

    private static void requireXhr(String xrw) {
        if (!"XMLHttpRequest".equals(xrw)) throw new ApiException(ErrorCode.FORBIDDEN, "Missing X-Requested-With header");
    }
}
