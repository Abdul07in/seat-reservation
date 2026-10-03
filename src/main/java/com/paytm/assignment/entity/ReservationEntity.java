package com.paytm.assignment.entity;

import com.paytm.assignment.constant.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationEntity extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.CONFIRMED;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "cancelled_at")
    private java.time.Instant cancelledAt;

    public ReservationEntity(UUID showId, String userId, long amountPaise) {
        this.showId = showId;
        this.userId = userId;
        this.amountPaise = amountPaise;
    }

    public void cancel(java.time.Instant cancelledAt) {
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }
}
