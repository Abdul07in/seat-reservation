package com.paytm.assignment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.assignment.constant.ApiConstants;
import com.paytm.assignment.constant.ApiErrorCode;
import com.paytm.assignment.dto.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256;

@Configuration
public class JwtSecurityConfiguration {

    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.jwt.secret}") String secret,
                          @Value("${spring.security.jwt.issuer}") String issuer) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes for HS256");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(HS256).build();
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> subjectValidator = jwt -> jwt.getSubject() != null && !jwt.getSubject().isBlank()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error(
                        "invalid_token", "Token subject (sub) is required", null));
        decoder.setJwtValidator(jwt -> {
            OAuth2TokenValidatorResult defaultResult = defaults.validate(jwt);
            return defaultResult.hasErrors() ? defaultResult : subjectValidator.validate(jwt);
        });
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/users").permitAll()
                        .requestMatchers(HttpMethod.GET, "/users").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/users/*").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/users/*").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/users/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/shows").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/shows/*/reserve").authenticated()
                        .requestMatchers(HttpMethod.POST, "/reservations/*/cancel").authenticated()
                        .requestMatchers(HttpMethod.GET, "/shows/*").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roleClaimsConverter()))
                        .authenticationEntryPoint((request, response, exception) -> writeError(objectMapper, request, response,
                                HttpStatus.UNAUTHORIZED, ApiErrorCode.UNAUTHORIZED, "A valid bearer token is required"))
                        .accessDeniedHandler((request, response, exception) -> writeError(objectMapper, request, response,
                                HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, "You are not allowed to perform this operation")));
        http.addFilterAfter(new AuditActorFilter(), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private Converter<Jwt, ? extends AbstractAuthenticationToken> roleClaimsConverter() {
        return jwt -> {
            Object rawRoles = jwt.getClaims().get("roles");
            List<SimpleGrantedAuthority> authorities = rawRoles instanceof List<?> roles
                    ? roles.stream().filter(String.class::isInstance).map(String.class::cast)
                    .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                    .map(SimpleGrantedAuthority::new).toList()
                    : List.of();
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
    }

    private static void writeError(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
                                   HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String requestId = org.slf4j.MDC.get(ApiConstants.REQUEST_ID_HEADER);
        mapper.writeValue(response.getOutputStream(), new ApiErrorResponse(Instant.now(), status.value(), code,
                message, request.getRequestURI(), requestId, Map.of()));
    }
}
