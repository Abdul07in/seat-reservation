package com.paytm.assignment.service;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.paytm.assignment.constant.UserRole;
import com.paytm.assignment.dto.response.TokenResponse;
import com.paytm.assignment.entity.UserEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class JwtTokenService {

    private static final long TOKEN_LIFETIME_SECONDS = 3600;
    private final JwtEncoder jwtEncoder;
    private final String issuer;

    public JwtTokenService(@Value("${spring.security.jwt.secret}") String secret,
                           @Value("${spring.security.jwt.issuer}") String issuer) {
        byte[] key = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes for HS256");
        }
        this.jwtEncoder = new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
                new ImmutableSecret<SecurityContext>(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")));
        this.issuer = issuer;
    }

    public TokenResponse issue(UserEntity user) {
        Instant now = Instant.now();
        UserRole role = user.getRole();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(TOKEN_LIFETIME_SECONDS))
                .claim("roles", List.of(role.name()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", TOKEN_LIFETIME_SECONDS, user.getId(), role);
    }
}
