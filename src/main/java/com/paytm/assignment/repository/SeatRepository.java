package com.paytm.assignment.repository;

import com.paytm.assignment.entity.SeatEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<SeatEntity, UUID> {

    List<SeatEntity> findAllByShow_IdOrderBySeatLabel(UUID showId);

    @Query(value = "SELECT COUNT(*) FROM seats s WHERE NOT EXISTS (" +
            "SELECT 1 FROM reservation_seats rs JOIN reservations r ON r.id = rs.reservation_id " +
            "WHERE rs.seat_id = s.id AND r.status = 'CONFIRMED')", nativeQuery = true)
    @Transactional(readOnly = true)
    long countAvailableSeats();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SeatEntity s where s.show.id = :showId and s.seatLabel in :labels order by s.seatLabel")
    List<SeatEntity> lockByShowIdAndSeatLabelInOrderBySeatLabel(@Param("showId") UUID showId,
                                                                @Param("labels") List<String> labels);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SeatEntity s where s.id in " +
            "(select rs.seat.id from ReservationSeatEntity rs where rs.reservation.id = :reservationId) " +
            "order by s.seatLabel")
    List<SeatEntity> lockAllByReservationIdOrderBySeatLabel(@Param("reservationId") UUID reservationId);
}
