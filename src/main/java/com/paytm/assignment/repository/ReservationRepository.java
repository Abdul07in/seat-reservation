package com.paytm.assignment.repository;

import com.paytm.assignment.constant.ReservationStatus;
import com.paytm.assignment.entity.ReservationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReservationEntity r where r.id = :id")
    java.util.Optional<ReservationEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("select count(rs) from ReservationSeatEntity rs " +
            "where rs.reservation.showId = :showId and rs.reservation.userId = :userId " +
            "and rs.reservation.status = :status")
    long countSeatsByShowAndUserAndStatus(@Param("showId") UUID showId, @Param("userId") String userId,
                                          @Param("status") ReservationStatus status);
}
