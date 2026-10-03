package com.paytm.assignment.repository;

import com.paytm.assignment.entity.SeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<SeatEntity, UUID> {

    List<SeatEntity> findAllByShow_IdOrderBySeatLabel(UUID showId);
}
