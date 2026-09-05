package com.system.recovery.service;

import com.system.recovery.dto.RecoveryDecision;
import com.system.recovery.dto.SignalBundle;
import com.system.recovery.model.FailureBucket;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

/** Deterministic demo harness; it bypasses neither the decision nor audit/orchestration code. */
@Service
public class DemoScenarioService {
    private final GeminiDecisionClient decisionClient;
    private final RecoveryOrchestrator orchestrator;
    public DemoScenarioService(GeminiDecisionClient decisionClient, RecoveryOrchestrator orchestrator) { this.decisionClient = decisionClient; this.orchestrator = orchestrator; }
    public Map<String, Object> scenarioA() {
        SignalBundle bundle = new SignalBundle(UUID.randomUUID(), "demo-customer-a", "INSUFFICIENT_FUNDS", BigDecimal.valueOf(4200), 1, 0,
                new SignalBundle.CustomerOrderHistory(3, 3, 1, OptionalInt.of(41)), 2, OptionalInt.of(36));
        RecoveryDecision decision = decisionClient.decide(bundle);
        String outcome = orchestrator.handle(new RecoveryDecisionService.DecisionResult(FailureBucket.SOFT, bundle, decision));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", "A"); result.put("order_id", bundle.orderId()); result.put("decision", decision); result.put("orchestrator_outcome", outcome);
        result.put("order_status", "PAID"); result.put("note", "Demo retry completion is compressed; no checkout-path behavior changed."); return result;
    }
    public Map<String, Object> scenarioB() {
        SignalBundle bundle = new SignalBundle(UUID.randomUUID(), "demo-customer-b", "DO_NOT_HONOR", BigDecimal.valueOf(4200), 3, 2,
                new SignalBundle.CustomerOrderHistory(0, 0, 0, OptionalInt.empty()), 2, OptionalInt.of(36));
        RecoveryDecision decision = decisionClient.decide(bundle);
        String outcome = orchestrator.handle(new RecoveryDecisionService.DecisionResult(FailureBucket.SOFT, bundle, decision));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", "B"); result.put("order_id", bundle.orderId()); result.put("decision", decision); result.put("orchestrator_outcome", outcome);
        result.put("approval_queue", orchestrator.pending()); return result;
    }
}
