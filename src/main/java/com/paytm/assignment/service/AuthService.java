package com.paytm.assignment.service;

import com.paytm.assignment.constant.ApiErrorCode;
import com.paytm.assignment.dto.request.SignInRequest;
import com.paytm.assignment.dto.response.TokenResponse;
import com.paytm.assignment.entity.UserEntity;
import com.paytm.assignment.exception.ApiException;
import com.paytm.assignment.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtTokenService jwtTokenService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    @Transactional(readOnly = true)
    public TokenResponse signIn(SignInRequest request) {
        String identifier = request.identifier().trim().toLowerCase(Locale.ROOT);
        UserEntity user = users.findByEmailIgnoreCase(identifier)
                .filter(UserEntity::isActive)
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTHENTICATION_FAILED,
                        "Invalid username/email or password"));
        return jwtTokenService.issue(user);
    }
}
