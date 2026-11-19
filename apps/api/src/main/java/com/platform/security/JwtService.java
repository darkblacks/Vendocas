package com.platform.security;

import com.platform.shared.AppProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final AppProperties props;

    public JwtService(JwtEncoder encoder, AppProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public String issueAccessToken(UUID userId, String email, boolean mfa, String platformRole) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("vendocas")
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.security().accessTokenTtl()))
                .claim("email", email)
                .claim("amr", mfa ? List.of("pwd", "mfa") : List.of("pwd"));
        if (platformRole != null) claims.claim("pr", platformRole);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }
}
