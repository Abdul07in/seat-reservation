package com.paytm.assignment.controller;

import com.paytm.assignment.dto.request.SignInRequest;
import com.paytm.assignment.dto.response.TokenResponse;
import com.paytm.assignment.service.AuthService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Slf4j
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signin")
    public ResponseEntity<TokenResponse> signIn(@Valid @RequestBody SignInRequest request) {
        log.info("sign_in_request_received");
        TokenResponse response = authService.signIn(request);
        log.info("sign_in_response_ready status=200 role={}", response.role());
        return ResponseEntity.ok(response);
    }
}
