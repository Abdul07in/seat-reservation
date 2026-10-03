package com.paytm.assignment.repository;

import com.paytm.assignment.entity.ReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {
}
