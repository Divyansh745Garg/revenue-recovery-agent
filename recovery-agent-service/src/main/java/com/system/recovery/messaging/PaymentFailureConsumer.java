package com.system.recovery.messaging;

import com.system.recovery.dto.PaymentFailedEvent;
import com.system.recovery.service.RecoveryDecisionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class PaymentFailureConsumer {
    private final RecoveryDecisionService decisions;
    private final com.system.recovery.service.RecoveryOrchestrator orchestrator;
    public PaymentFailureConsumer(RecoveryDecisionService decisions, com.system.recovery.service.RecoveryOrchestrator orchestrator) { this.decisions = decisions; this.orchestrator = orchestrator; }
    @RabbitListener(bindings = @QueueBinding(value = @Queue(value = "payment.failed.recovery.queue", durable = "true"),
            exchange = @Exchange(value = "payment.exchange", type = "topic"), key = "payment.failed.routing.key"))
    public void consume(PaymentFailedEvent event) {
        RecoveryDecisionService.DecisionResult result = decisions.decide(event);
        log.info("Payment failure {} classified {} with action {}; {}", event.orderId(), result.bucket(), result.decision().action(), orchestrator.handle(result));
    }
}
