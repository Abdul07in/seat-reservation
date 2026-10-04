package com.paytm.assignment.serviceImpl;

import com.paytm.assignment.service.AuthService;
import com.paytm.assignment.service.JwtTokenService;
import com.paytm.assignment.constant.ApiErrorCode;
import com.paytm.assignment.dto.request.SignInRequest;
import com.paytm.assignment.dto.response.TokenResponse;
import com.paytm.assignment.entity.UserEntity;
import com.paytm.assignment.exception.ApiException;
import com.paytm.assignment.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    @Transactional(readOnly = true)
    public TokenResponse signIn(SignInRequest request) {
        String identifier = request.identifier().trim().toLowerCase(Locale.ROOT);
        UserEntity user = users.findByEmailIgnoreCase(identifier)
                .filter(UserEntity::isActive)
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTHENTICATION_FAILED,
                        "Invalid username/email or password"));
        TokenResponse response = jwtTokenService.issue(user);
        log.info("sign_in_succeeded role={} user_id={}", user.getRole(), user.getId());
        return response;
    }
}
