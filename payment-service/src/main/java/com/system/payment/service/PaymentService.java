package com.system.payment.service;

import com.system.payment.config.RabbitMQConfig;
import com.system.payment.dto.OrderEvent;
import com.system.payment.dto.PaymentEvent;
import com.system.payment.dto.PaymentFailedEvent;
import com.system.payment.model.Payment;
import com.system.payment.model.PaymentFailureReason;
import com.system.payment.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j; // <-- Removed @RequiredArgsConstructor
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.UUID;

@Service
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final RabbitTemplate rabbitTemplate;
    private final FailureInjector failureInjector;

    // 1. Declare the counter
    private final Counter paymentDeclinedCounter;

    @Value("${payment.simulate-success:true}")
    private boolean simulateSuccess;

    // 2. Custom Constructor: Inject dependencies AND the MeterRegistry
    public PaymentService(PaymentRepository paymentRepository,
                          RabbitTemplate rabbitTemplate,
                          FailureInjector failureInjector,
                          MeterRegistry registry) {
        this.paymentRepository = paymentRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.failureInjector = failureInjector;

        // 3. Build and register the custom business metric with Prometheus!
        this.paymentDeclinedCounter = Counter.builder("business_payments_declined_total")
                .description("Total number of declined payments")
                .register(registry);
    }

    @Transactional
    public void processPayment(OrderEvent event) {
        log.info("Processing payment of ${} for Order {}", event.totalAmount(), event.orderId());

        // Simulate external payment gateway call
        boolean paymentSuccessful = simulatePaymentGateway();

        if (paymentSuccessful) {
            // ==========================================
            // PATH A: PAYMENT SUCCESS
            // ==========================================
            String mockTransactionId = "txn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            Payment payment = Payment.builder()
                    .orderId(event.orderId())
                    .userId(event.userId())
                    .amount(event.totalAmount())
                    .status("SUCCESS")
                    .transactionId(mockTransactionId)
                    .build();

            paymentRepository.save(payment);
            log.info("✅ Payment SUCCESS. Transaction ID: {} saved to database.", mockTransactionId);

            PaymentEvent paymentEvent = new PaymentEvent(event.orderId(), "COMPLETED");
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.PAYMENT_EXCHANGE,
                    RabbitMQConfig.PAYMENT_COMPLETED_ROUTING_KEY,
                    paymentEvent
            );
            log.info("PaymentCompleted event published for order: {}", event.orderId());

        } else {
            // ==========================================
            // PATH B: PAYMENT FAILED (TRIGGER SAGA ROLLBACK)
            // ==========================================
            FailureInjector.FailureInjection injection = failureInjector.inject();
            PaymentFailureReason declineCode = injection.declineCode();
            log.error("❌ Payment Declined for Order: {} with code: {}", event.orderId(), declineCode);

            // 4. INCREMENT THE GRAFANA METRIC!
            paymentDeclinedCounter.increment();

            // Save the failed attempt to the database for our records
            Payment payment = Payment.builder()
                    .orderId(event.orderId())
                    .userId(event.userId())
                    .amount(event.totalAmount())
                    .status("FAILED")
                    .declineCode(declineCode)
                    .build();
            paymentRepository.save(payment);

            // Broadcast the failure to RabbitMQ so the Order Service can catch it
            PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                    event.orderId(),
                    humanReadableReason(declineCode),
                    declineCode
            );

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.PAYMENT_EXCHANGE,
                    "payment.failed.routing.key",
                    failedEvent
            );

            log.info("PaymentFailedEvent published with decline code {}. Awaiting Order Service to initiate rollback.", declineCode);
        }
    }

    // Helper method to let you test the Rollback!
    private boolean simulatePaymentGateway() {
        return simulateSuccess;
    }
    private String humanReadableReason(PaymentFailureReason declineCode) {
        return declineCode.name();
    }
}
