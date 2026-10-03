package com.paytm.assignment.service;

import com.paytm.assignment.dto.response.TokenResponse;
import com.paytm.assignment.entity.UserEntity;

public interface JwtTokenService {
    TokenResponse issue(UserEntity user);
}
