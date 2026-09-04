package com.system.recovery.service;

import com.system.recovery.dto.PaymentFailedEvent;
import com.system.recovery.dto.SignalBundle;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.OptionalInt;

/** Builds a deterministic synthetic customer-history signal until a customer data API exists. */
@Service
public class SignalBundleService {
    public SignalBundle build(PaymentFailedEvent event) {
        int seed = Math.abs(event.orderId().hashCode());
        int priorOrders = seed % 8;
        int successful = priorOrders == 0 ? 0 : Math.max(0, priorOrders - (seed % 3));
        int sameReasonDeclines = priorOrders == 0 ? 0 : seed % Math.min(4, priorOrders + 1);
        OptionalInt recovered = sameReasonDeclines > 0 && seed % 100 < 60
                ? OptionalInt.of(18 + (seed % 37)) : OptionalInt.empty();
        int populationPeak = 36;
        return new SignalBundle(event.orderId(), "synthetic-" + (seed % 10000), event.declineCode(),
                BigDecimal.valueOf(500 + (seed % 9500)), 1, 0,
                new SignalBundle.CustomerOrderHistory(priorOrders, successful, sameReasonDeclines, recovered),
                seed % 6, OptionalInt.of(populationPeak));
    }
}
