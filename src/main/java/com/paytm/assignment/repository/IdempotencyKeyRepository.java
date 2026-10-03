package com.paytm.assignment.repository;

import com.paytm.assignment.entity.IdempotencyKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {

    Optional<IdempotencyKeyEntity> findByShowIdAndUserIdAndIdempotencyKey(
            UUID showId, String userId, String idempotencyKey);
}
