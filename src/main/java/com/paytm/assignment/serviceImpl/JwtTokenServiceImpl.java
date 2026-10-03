package com.paytm.assignment.serviceImpl;

import com.paytm.assignment.service.JwtTokenService;
import com.paytm.assignment.config.JwtProperties;
import com.paytm.assignment.constant.UserRole;
import com.paytm.assignment.dto.response.TokenResponse;
import com.paytm.assignment.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class JwtTokenServiceImpl implements JwtTokenService {

    private static final long TOKEN_LIFETIME_SECONDS = 3600;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public TokenResponse issue(UserEntity user) {
        Instant now = Instant.now();
        UserRole role = user.getRole();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
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
