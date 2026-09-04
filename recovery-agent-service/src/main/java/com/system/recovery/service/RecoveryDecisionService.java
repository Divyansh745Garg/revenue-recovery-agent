package com.system.recovery.service;

import com.system.recovery.dto.PaymentFailedEvent;
import com.system.recovery.dto.RecoveryDecision;
import com.system.recovery.dto.SignalBundle;
import com.system.recovery.model.FailureBucket;
import com.system.recovery.model.RecoveryAction;
import org.springframework.stereotype.Service;

@Service
public class RecoveryDecisionService {
    private final FailureClassifier classifier;
    private final SignalBundleService signals;
    private final GeminiDecisionClient gemini;
    public RecoveryDecisionService(FailureClassifier classifier, SignalBundleService signals, GeminiDecisionClient gemini) {
        this.classifier = classifier; this.signals = signals; this.gemini = gemini;
    }
    public DecisionResult decide(PaymentFailedEvent event) {
        FailureBucket bucket = classifier.classify(event.declineCode());
        SignalBundle bundle = signals.build(event);
        if (bucket == FailureBucket.TECHNICAL) return new DecisionResult(bucket, bundle,
                new RecoveryDecision(RecoveryAction.RETRY_SILENT, 0, "high", false, "Technical failure: existing retry path."));
        if (bucket == FailureBucket.TERMINAL) return new DecisionResult(bucket, bundle,
                new RecoveryDecision(RecoveryAction.STOP, 0, "high", false, "Terminal issuer decline: notify and stop."));
        return new DecisionResult(bucket, bundle, gemini.decide(bundle));
    }
    public record DecisionResult(FailureBucket bucket, SignalBundle signalBundle, RecoveryDecision decision) {}
}
