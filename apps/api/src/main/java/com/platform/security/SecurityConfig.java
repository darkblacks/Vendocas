package com.platform.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.platform.shared.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, AppProperties props) throws Exception {
        http
            // Stateless bearer-token API: no cookie-based authentication except the refresh cookie,
            // which is SameSite=Strict, path-scoped and additionally requires a custom header.
            .csrf(c -> c.disable())
            .cors(c -> c.configurationSource(cors(props)))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/login/mfa",
                        "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/verify-email",
                        "/api/v1/auth/resend-verification", "/api/v1/auth/password/forgot", "/api/v1/auth/password/reset").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/billing/plans", "/api/v1/public/**").permitAll()
                .requestMatchers("/api/v1/public/**", "/api/v1/track/**", "/api/v1/webhooks/**", "/l/**").permitAll()
                .requestMatchers("/api/v1/integrations/inbound/**").permitAll() // API-key authenticated in controller
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/actuator/**").denyAll()
                // Control Center status: authenticated by its own shared secret in the controller (404 when not configured).
                .requestMatchers(HttpMethod.GET, "/internal/status").permitAll()
                .requestMatchers(HttpMethod.PUT, "/internal/platform-settings").permitAll() // CONTROL_ADMIN_TOKEN checked in the controller
                .requestMatchers("/internal/commercial/**", "/internal/merchants", "/internal/merchants/**").permitAll() // CONTROL_ADMIN_TOKEN checked in ControlAuth
                .requestMatchers("/internal/**").denyAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> {}));
        return http.build();
    }

    private CorsConfigurationSource cors(AppProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.corsAllowedOrigins() == null ? List.of() : props.corsAllowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", TenantInterceptor.HEADER, "X-Requested-With", "Idempotency-Key"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        // Tracking endpoint is called from merchants' websites (any origin), without credentials.
        CorsConfiguration track = new CorsConfiguration();
        track.addAllowedOriginPattern("*");
        track.setAllowedMethods(List.of("POST", "OPTIONS"));
        track.setAllowedHeaders(List.of("Content-Type"));
        track.setAllowCredentials(false);
        source.registerCorsConfiguration("/api/v1/track/**", track);
        source.registerCorsConfiguration("/api/v1/public/forms/**", track);
        return source;
    }

    @Bean
    SecretKey jwtKey(AppProperties props) {
        String secret = props.security().jwtSecret();
        if (secret == null || secret.isBlank()) throw new IllegalStateException("app.security.jwt-secret (JWT_SECRET) is required");
        byte[] raw = Base64.getDecoder().decode(secret.trim());
        if (raw.length < 32) throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes");
        return new SecretKeySpec(raw, "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey) {
        return NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
