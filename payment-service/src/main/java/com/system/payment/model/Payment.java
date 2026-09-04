package com.system.payment.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // We store the Order ID as a reference, but NOT as a foreign key!
    // (Microservice rule: Never use foreign keys across different databases)
    @Column(nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status; // SUCCESS, FAILED

    /** Synthetic classification for a failed mock payment; status remains authoritative. */
    @Enumerated(EnumType.STRING)
    private PaymentFailureReason declineCode;

    @Column(unique = true)
    private String transactionId;

    @CreationTimestamp
    private LocalDateTime paymentDate;
}