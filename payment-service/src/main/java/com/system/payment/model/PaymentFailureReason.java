package com.system.payment.model;

/** Simulated payment-decline classes, separate from the payment status. */
public enum PaymentFailureReason {
    GATEWAY_TIMEOUT, GATEWAY_5XX,
    CARD_EXPIRED, CARD_STOLEN_BLOCKED, FRAUD_HARD_BLOCK,
    INSUFFICIENT_FUNDS, DO_NOT_HONOR, OTP_3DS_FAILED, RISK_SOFT_HOLD
}
