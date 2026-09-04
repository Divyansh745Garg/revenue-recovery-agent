package com.system.recovery.service;

import com.system.recovery.dto.PaymentFailedEvent;
import com.system.recovery.model.FailureBucket;
import com.system.recovery.model.RecoveryAction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Batch-only synchronous harness; it does not alter RabbitMQ or checkout processing. */
@Service
public class BatchRunner {
    private static final String[] REASONS = {"GATEWAY_TIMEOUT", "GATEWAY_5XX", "CARD_EXPIRED", "CARD_STOLEN_BLOCKED", "FRAUD_HARD_BLOCK", "INSUFFICIENT_FUNDS", "DO_NOT_HONOR", "OTP_3DS_FAILED", "RISK_SOFT_HOLD"};
    private final RecoveryDecisionService decisions;
    private final RecoveryOrchestrator orchestrator;
    public BatchRunner(RecoveryDecisionService decisions, RecoveryOrchestrator orchestrator) { this.decisions = decisions; this.orchestrator = orchestrator; }
    public Map<String, Object> run(int count) {
        int soft = 0, recovered = 0, falsePositive = 0;
        BigDecimal revenue = BigDecimal.ZERO, atRisk = BigDecimal.ZERO;
        int[] curve = new int[97];
        for (int i = 0; i < count; i++) {
            String code = REASONS[i % REASONS.length];
            var result = decisions.decide(new PaymentFailedEvent(UUID.randomUUID(), code, code));
            String execution = orchestrator.handle(result);
            if (result.bucket() != FailureBucket.SOFT) continue;
            soft++;
            BigDecimal amount = result.signalBundle().orderValue();
            atRisk = atRisk.add(amount);
            curve[Math.min(96, Math.max(1, result.decision().delayHours()))]++;
            boolean success = result.decision().action() == RecoveryAction.RETRY_SILENT && "EXECUTED".equals(execution);
            if (success) { recovered++; revenue = revenue.add(amount); }
            if (result.decision().requiresHumanApproval()) falsePositive++;
        }
        double recoveryPercent = soft == 0 ? 0.0 : (100.0 * (double) recovered / (double) soft);
        double falsePositivePercent = soft == 0 ? 0.0 : (100.0 * (double) falsePositive / (double) soft);
        
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("failed_payments", count); output.put("soft_declines", soft); output.put("recovered", recovered);
        output.put("recovery_percent", recoveryPercent); output.put("false_positive_percent", falsePositivePercent);
        output.put("revenue_recovered", revenue); output.put("revenue_at_risk", atRisk); output.put("recovery_curve", curve);
        System.out.printf("BATCH METRICS SUMMARY%nRecovery %%: %.2f%nFalse Positive %%: %.2f%nRevenue Recovered: %s%n", recoveryPercent, falsePositivePercent, revenue);
        return output;
    }
}
