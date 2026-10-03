package com.paytm.assignment.controller;

import com.paytm.assignment.dto.request.ReserveSeatsRequest;
import com.paytm.assignment.dto.response.CancellationResponse;
import com.paytm.assignment.dto.response.ReservationResponse;
import com.paytm.assignment.service.ReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
        return ResponseEntity.status(HttpStatus.CREATED).body(reservationService.reserve(id, authentication.getName(), idempotencyKey.trim(), request));
    }

    @PostMapping("/reservations/{id}/cancel")
    public CancellationResponse cancel(@PathVariable UUID id, Authentication authentication) {
        return new CancellationResponse(id.toString(), reservationService.cancel(id, authentication.getName()));
    }
}
