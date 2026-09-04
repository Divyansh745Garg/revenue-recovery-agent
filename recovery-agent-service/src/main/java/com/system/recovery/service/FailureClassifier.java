package com.system.recovery.service;

import com.system.recovery.model.FailureBucket;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class FailureClassifier {
    private static final Set<String> TECHNICAL = Set.of("GATEWAY_TIMEOUT", "GATEWAY_5XX");
    private static final Set<String> TERMINAL = Set.of("CARD_EXPIRED", "CARD_STOLEN_BLOCKED", "FRAUD_HARD_BLOCK");
    public FailureBucket classify(String reason) {
        if (TECHNICAL.contains(reason)) return FailureBucket.TECHNICAL;
        if (TERMINAL.contains(reason)) return FailureBucket.TERMINAL;
        return FailureBucket.SOFT;
    }
}
