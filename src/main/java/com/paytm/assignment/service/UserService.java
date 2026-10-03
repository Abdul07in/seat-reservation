package com.paytm.assignment.service;

import com.paytm.assignment.dto.request.CreateUserRequest;
import com.paytm.assignment.dto.request.UpdateUserRequest;
import com.paytm.assignment.dto.response.UserResponse;

import java.util.List;
import java.util.UUID;

public interface UserService {
    UserResponse create(CreateUserRequest request);
    List<UserResponse> list();
    UserResponse get(UUID id, String actorId, boolean admin);
    UserResponse update(UUID id, UpdateUserRequest request, String actorId, boolean admin);
    void delete(UUID id, String actorId, boolean admin);
}
