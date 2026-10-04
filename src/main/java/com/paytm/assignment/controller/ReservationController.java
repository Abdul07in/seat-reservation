package com.paytm.assignment.controller;

import com.paytm.assignment.dto.request.ReserveSeatsRequest;
import com.paytm.assignment.dto.response.CancellationResponse;
import com.paytm.assignment.dto.response.ReservationResponse;
import com.paytm.assignment.service.ReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@Slf4j
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody ReserveSeatsRequest request,
            Authentication authentication
    ) {
        log.info("reservation_request_received show_id={} seat_count={}", id, request.seats().size());
        ReservationResponse reservation = reservationService.reserve(id, authentication.getName(), idempotencyKey.trim(), request);
        log.info("reservation_response_ready status=201 show_id={} reservation_id={}", id, reservation.reservationId());
        return ResponseEntity.status(HttpStatus.CREATED).body(reservation);
    }

    @PostMapping("/reservations/{id}/cancel")
    public CancellationResponse cancel(@PathVariable UUID id, Authentication authentication) {
        log.info("cancellation_request_received reservation_id={}", id);
        String status = reservationService.cancel(id, authentication.getName());
        log.info("cancellation_response_ready reservation_id={} status={}", id, status);
        return new CancellationResponse(id.toString(), status);
    }
}
