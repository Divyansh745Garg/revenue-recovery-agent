package com.system.recovery.dto;

import java.util.UUID;

public record PaymentFailedEvent(UUID orderId, String reason, String declineCode) {}
