package com.paytm.assignment.repository;

import com.paytm.assignment.constant.ReservationStatus;
import com.paytm.assignment.entity.ReservationSeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeatEntity, UUID> {

    @Query("select rs.seat.id from ReservationSeatEntity rs " +
            "where rs.seat.id in :seatIds and rs.reservation.status = :status")
    List<UUID> findActiveSeatIds(@Param("seatIds") List<UUID> seatIds,
                                 @Param("status") ReservationStatus status);

    @Query("select rs from ReservationSeatEntity rs join fetch rs.seat join fetch rs.reservation " +
            "where rs.reservation.id = :reservationId order by rs.seat.seatLabel")
    List<ReservationSeatEntity> findAllByReservationIdOrderBySeatLabel(@Param("reservationId") UUID reservationId);

    @Query("select rs from ReservationSeatEntity rs join fetch rs.seat join fetch rs.reservation " +
            "where rs.seat.show.id = :showId and rs.reservation.status = :status order by rs.seat.seatLabel")
    List<ReservationSeatEntity> findAllByShowIdAndReservationStatus(@Param("showId") UUID showId,
                                                                    @Param("status") ReservationStatus status);
}
