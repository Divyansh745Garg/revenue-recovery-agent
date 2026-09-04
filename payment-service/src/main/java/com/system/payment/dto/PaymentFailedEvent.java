package com.system.payment.dto;

import com.system.payment.model.PaymentFailureReason;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PaymentFailedEvent {
    private UUID orderId;
    private String reason;
    private PaymentFailureReason declineCode;

    public PaymentFailedEvent(UUID orderId, String reason) {
        this(orderId, reason, null);
    }
}