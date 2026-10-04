package com.paytm.assignment.serviceImpl;

import com.paytm.assignment.service.UserService;
import com.paytm.assignment.constant.ApiErrorCode;
import com.paytm.assignment.constant.UserRole;
import com.paytm.assignment.dto.request.CreateUserRequest;
import com.paytm.assignment.dto.request.UpdateUserRequest;
import com.paytm.assignment.dto.response.UserResponse;
import com.paytm.assignment.entity.UserEntity;
import com.paytm.assignment.exception.ApiException;
import com.paytm.assignment.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String email = normalizeEmail(request.email());
        ensureEmailAvailable(email);
        UserEntity saved = users.save(new UserEntity(email, passwordEncoder.encode(request.password()), UserRole.USER));
        log.info("user_created user_id={} role={}", saved.getId(), saved.getRole());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return users.findAll().stream().filter(UserEntity::isActive).map(UserServiceImpl::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id, String actorId, boolean admin) {
        ensureOwnerOrAdmin(id, actorId, admin);
        return toResponse(findActive(id));
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request, String actorId, boolean admin) {
        ensureOwnerOrAdmin(id, actorId, admin);
        UserEntity user = findActive(id);
        String email = normalizeEmail(request.email());
        if (!user.getEmail().equalsIgnoreCase(email)) {
            ensureEmailAvailable(email);
        }
        String passwordHash = request.password() == null || request.password().isBlank()
                ? null : passwordEncoder.encode(request.password());
        user.update(email, passwordHash);
        return toResponse(user);
    }

    @Transactional
    public void delete(UUID id, String actorId, boolean admin) {
        ensureOwnerOrAdmin(id, actorId, admin);
        UserEntity user = findActive(id);
        user.deactivate();
        log.info("user_deactivated user_id={}", user.getId());
    }

    private UserEntity findActive(UUID id) {
        return users.findById(id).filter(UserEntity::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "User not found"));
    }

    private void ensureEmailAvailable(String email) {
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.EMAIL_ALREADY_EXISTS, "Email is already registered");
        }
    }

    private static void ensureOwnerOrAdmin(UUID targetId, String actorId, boolean admin) {
        if (!admin && !targetId.toString().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, "You are not allowed to access this user");
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static UserResponse toResponse(UserEntity user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.isActive());
    }
}
