package com.system.payment.service;

import com.system.payment.model.PaymentFailureReason;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ThreadLocalRandom;

/** Generates 35% technical, 20% terminal, and 45% soft synthetic decline data. */
@Component
public class FailureInjector {
    private static final int WINDOW_HOURS = 96;
    private static final int PEAK_HOURS = 36;

    public FailureInjection inject() {
        PaymentFailureReason reason = sampleReason();
        CustomerHistory history = sampleHistory(reason);
        OptionalInt recoveryHours = isSoft(reason) ? OptionalInt.of(sampleRecoveryHours()) : OptionalInt.empty();
        return new FailureInjection(reason, history, recoveryHours, generatePopulationRecoveryCurve());
    }

    /** Non-flat recovery timing dataset for a later batch run and recovery-service signal bundle. */
    public List<RecoveryCurvePoint> generatePopulationRecoveryCurve() {
        int[] bins = new int[WINDOW_HOURS + 1];
        for (int i = 0; i < 500; i++) bins[sampleRecoveryHours()]++;
        List<RecoveryCurvePoint> curve = new ArrayList<>(WINDOW_HOURS);
        for (int hour = 1; hour <= WINDOW_HOURS; hour++) curve.add(new RecoveryCurvePoint(hour, bins[hour]));
        return List.copyOf(curve);
    }

    private PaymentFailureReason sampleReason() {
        int draw = ThreadLocalRandom.current().nextInt(100);
        if (draw < 35) return ThreadLocalRandom.current().nextBoolean() ? PaymentFailureReason.GATEWAY_TIMEOUT : PaymentFailureReason.GATEWAY_5XX;
        if (draw < 55) return switch (ThreadLocalRandom.current().nextInt(3)) {
            case 0 -> PaymentFailureReason.CARD_EXPIRED;
            case 1 -> PaymentFailureReason.CARD_STOLEN_BLOCKED;
            default -> PaymentFailureReason.FRAUD_HARD_BLOCK;
        };
        return switch (ThreadLocalRandom.current().nextInt(4)) {
            case 0 -> PaymentFailureReason.INSUFFICIENT_FUNDS;
            case 1 -> PaymentFailureReason.DO_NOT_HONOR;
            case 2 -> PaymentFailureReason.OTP_3DS_FAILED;
            default -> PaymentFailureReason.RISK_SOFT_HOLD;
        };
    }

    private CustomerHistory sampleHistory(PaymentFailureReason reason) {
        int orders = ThreadLocalRandom.current().nextInt(13);
        int successes = orders == 0 ? 0 : ThreadLocalRandom.current().nextInt(orders + 1);
        int sameReasonDeclines = orders == 0 ? 0 : ThreadLocalRandom.current().nextInt(Math.min(4, orders) + 1);
        OptionalInt recovered = isSoft(reason) && sameReasonDeclines > 0 && ThreadLocalRandom.current().nextInt(100) < 60
                ? OptionalInt.of(sampleRecoveryHours()) : OptionalInt.empty();
        return new CustomerHistory(orders, successes, sameReasonDeclines, recovered);
    }

    private int sampleRecoveryHours() {
        double draw = ThreadLocalRandom.current().nextDouble();
        double mode = (double) PEAK_HOURS / WINDOW_HOURS;
        double triangular = draw < mode ? Math.sqrt(draw * mode) : 1 - Math.sqrt((1 - draw) * (1 - mode));
        return Math.max(1, Math.min(WINDOW_HOURS, (int) Math.round(triangular * WINDOW_HOURS)));
    }

    private boolean isSoft(PaymentFailureReason reason) {
        return switch (reason) {
            case INSUFFICIENT_FUNDS, DO_NOT_HONOR, OTP_3DS_FAILED, RISK_SOFT_HOLD -> true;
            default -> false;
        };
    }

    public record FailureInjection(PaymentFailureReason declineCode, CustomerHistory customerHistory,
                                   OptionalInt hoursUntilRetrySucceeds, List<RecoveryCurvePoint> populationRecoveryCurve) {}
    public record CustomerHistory(int priorOrderCount, int priorSuccessfulOrders, int priorSameReasonDeclines,
                                  OptionalInt priorSameReasonRecoveredWithinHours) {}
    public record RecoveryCurvePoint(int hoursSinceDecline, int recoveries) {}
}
