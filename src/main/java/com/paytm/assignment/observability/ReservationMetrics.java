package com.paytm.assignment.observability;

import com.paytm.assignment.repository.SeatRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

@Component
@Slf4j
public class ReservationMetrics {

    private final Counter confirmedReservations;
    private final Counter idempotentReplays;
    private final Counter cancellations;
    private final Map<String, Counter> declinesByReason;

    public ReservationMetrics(MeterRegistry registry, SeatRepository seats) {
        confirmedReservations = Counter.builder("seat.reservation.confirmed")
                .description("Successfully committed reservations")
                .register(registry);
        idempotentReplays = Counter.builder("seat.reservation.idempotent.replays")
                .description("Successful reservation retries returned from idempotency records")
                .register(registry);
        cancellations = Counter.builder("seat.reservation.cancellations")
                .description("Successfully committed reservation cancellations")
                .register(registry);
        declinesByReason = Map.of(
                "seat_unavailable", declineCounter(registry, "seat_unavailable"),
                "user_seat_limit", declineCounter(registry, "user_seat_limit"),
                "idempotency_key_reused", declineCounter(registry, "idempotency_key_reused"));
        Gauge.builder("seat.reservation.seats.available", seats, ReservationMetrics::readAvailableSeats)
                .description("Available seats across all shows, derived from persisted reservations")
                .register(registry);
    }

    public void reservationConfirmedAfterCommit() {
        afterCommit(confirmedReservations::increment);
    }

    public void idempotentReplayAfterCommit() {
        afterCommit(idempotentReplays::increment);
    }

    public void cancellationAfterCommit() {
        afterCommit(cancellations::increment);
    }

    public void declined(String reason) {
        Counter counter = declinesByReason.get(reason);
        if (counter != null) {
            counter.increment();
        }
    }

    private static Counter declineCounter(MeterRegistry registry, String reason) {
        return Counter.builder("seat.reservation.declined")
                .description("Reservation requests declined by bounded domain reason")
                .tag("reason", reason)
                .register(registry);
    }

    private static double readAvailableSeats(SeatRepository seats) {
        try {
            return seats.countAvailableSeats();
        } catch (RuntimeException exception) {
            log.error("available_seats_gauge_read_failed", exception);
            return Double.NaN;
        }
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
