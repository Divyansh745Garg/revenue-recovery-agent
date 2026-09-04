package com.system.recovery.dto;

import java.math.BigDecimal;
import java.util.OptionalInt;
import java.util.UUID;

public record SignalBundle(UUID orderId, String customerId, String declineReason, BigDecimal orderValue,
                           int attemptNumber, int priorRecoveryAttemptsThisOrder,
                           CustomerOrderHistory customerOrderHistory, int hoursSinceDecline,
                           OptionalInt populationRecoveryPeakHours) {
    public record CustomerOrderHistory(int totalPriorOrders, int priorOrdersSucceeded,
                                       int priorSameReasonDeclines, OptionalInt priorSameReasonRecoveredWithinHours) {}
}
