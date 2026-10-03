package com.paytm.assignment.service;

import com.paytm.assignment.dto.request.ReserveSeatsRequest;
import com.paytm.assignment.dto.response.ReservationResponse;

import java.util.UUID;

public interface ReservationService {
    ReservationResponse reserve(UUID showId, String userId, String idempotencyKey, ReserveSeatsRequest request);
    String cancel(UUID reservationId, String userId);
}
