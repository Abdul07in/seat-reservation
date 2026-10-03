package com.paytm.assignment.service;

import com.paytm.assignment.dto.request.SignInRequest;
import com.paytm.assignment.dto.response.TokenResponse;

public interface AuthService {
    TokenResponse signIn(SignInRequest request);
}
