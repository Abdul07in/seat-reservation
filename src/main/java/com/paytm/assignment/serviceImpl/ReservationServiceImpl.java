package com.paytm.assignment.serviceImpl;

import com.paytm.assignment.service.ReservationService;
import com.paytm.assignment.constant.ApiErrorCode;
import com.paytm.assignment.constant.ReservationStatus;
import com.paytm.assignment.dto.request.ReserveSeatsRequest;
import com.paytm.assignment.dto.response.ReservationResponse;
import com.paytm.assignment.entity.IdempotencyKeyEntity;
import com.paytm.assignment.entity.ReservationEntity;
import com.paytm.assignment.entity.ReservationSeatEntity;
import com.paytm.assignment.entity.SeatEntity;
import com.paytm.assignment.entity.ShowEntity;
import com.paytm.assignment.exception.ApiException;
import com.paytm.assignment.repository.IdempotencyKeyRepository;
import com.paytm.assignment.repository.ReservationRepository;
import com.paytm.assignment.repository.ReservationSeatRepository;
import com.paytm.assignment.repository.SeatRepository;
import com.paytm.assignment.repository.ShowRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final ReservationRepository reservations;
    private final ReservationSeatRepository reservationSeats;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final EntityManager entityManager;

    @Transactional
    public ReservationResponse reserve(UUID showId, String userId, String idempotencyKey, ReserveSeatsRequest request) {
        ShowEntity show = shows.findById(showId).orElseThrow(() -> notFound("Show not found"));
        acquireUserShowLock(showId, userId);

        List<String> requestedLabels = request.seats().stream().map(String::trim).sorted().toList();
        if (requestedLabels.stream().distinct().count() != requestedLabels.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR,
                    "Seat labels must be unique after trimming whitespace");
        }
        String fingerprint = fingerprint(requestedLabels);
        var previous = idempotencyKeys.findByShowIdAndUserIdAndIdempotencyKey(showId, userId, idempotencyKey);
        if (previous.isPresent()) {
            IdempotencyKeyEntity key = previous.get();
            if (!key.getRequestFingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency key was already used with a different seat request");
            }
            return responseFor(key.getReservationId());
        }

        List<SeatEntity> requestedSeats = seats.lockByShowIdAndSeatLabelInOrderBySeatLabel(showId, requestedLabels);
        if (requestedSeats.size() != requestedLabels.size()) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.SEAT_UNAVAILABLE,
                    "One or more requested seats do not exist for this show");
        }
        List<UUID> activeSeatIds = reservationSeats.findActiveSeatIds(
                requestedSeats.stream().map(SeatEntity::getId).toList(), ReservationStatus.CONFIRMED);
        if (!activeSeatIds.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.SEAT_UNAVAILABLE,
                    "One or more requested seats are already reserved");
        }

        long seatsAlreadyReserved = reservations.countSeatsByShowAndUserAndStatus(showId, userId, ReservationStatus.CONFIRMED);
        if (seatsAlreadyReserved + requestedSeats.size() > show.getPerUserLimit()) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.USER_SEAT_LIMIT_EXCEEDED,
                    "Reservation exceeds the per-user seat limit for this show");
        }

        long amountPaise;
        try {
            amountPaise = Math.multiplyExact(show.getPricePaise(), requestedSeats.size());
        } catch (ArithmeticException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST,
                    "Requested reservation total exceeds the supported amount");
        }
        ReservationEntity reservation = reservations.saveAndFlush(new ReservationEntity(showId, userId, amountPaise));
        reservationSeats.saveAll(requestedSeats.stream().map(seat -> new ReservationSeatEntity(reservation, seat)).toList());
        idempotencyKeys.save(new IdempotencyKeyEntity(showId, userId, idempotencyKey, fingerprint, reservation.getId()));
        return toResponse(reservation, requestedSeats.stream().map(SeatEntity::getSeatLabel).toList());
    }

    @Transactional
    public String cancel(UUID reservationId, String userId) {
        ReservationEntity initial = reservations.findById(reservationId)
                .orElseThrow(() -> notFound("Reservation not found"));
        if (!initial.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN,
                    "Only the reservation owner can cancel it");
        }
        acquireUserShowLock(initial.getShowId(), userId);
        ReservationEntity reservation = reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> notFound("Reservation not found"));
        if (!reservation.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN,
                    "Only the reservation owner can cancel it");
        }
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            seats.lockAllByReservationIdOrderBySeatLabel(reservationId);
            reservation.cancel(Instant.now());
        }
        return reservation.getStatus().name().toLowerCase(java.util.Locale.ROOT);
    }

    private ReservationResponse responseFor(UUID reservationId) {
        ReservationEntity reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                        "Idempotency record references a missing reservation"));
        List<String> labels = reservationSeats.findAllByReservationIdOrderBySeatLabel(reservationId).stream()
                .map(link -> link.getSeat().getSeatLabel()).toList();
        return toResponse(reservation, labels);
    }

    private void acquireUserShowLock(UUID showId, String userId) {
        String lockKey = showId + ":" + userId;
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .setParameter("lockKey", lockKey).getSingleResult();
    }

    private static String fingerprint(List<String> labels) {
        String canonical = labels.stream().sorted().collect(java.util.stream.Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static ReservationResponse toResponse(ReservationEntity reservation, List<String> labels) {
        return new ReservationResponse(reservation.getId().toString(), reservation.getShowId().toString(),
                reservation.getUserId(), labels, reservation.getAmountPaise(),
                reservation.getStatus().name().toLowerCase(java.util.Locale.ROOT));
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, message);
    }
}
