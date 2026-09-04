package com.system.recovery.service;

import com.system.recovery.dto.RecoveryDecision;
import com.system.recovery.dto.SignalBundle;
import com.system.recovery.model.RecoveryAction;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Gemini is advisory only; this class never exposes executable tools to the model. */
@Component
public class GeminiDecisionClient {
    private static final URI ENDPOINT = URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey = System.getenv("GEMINI_API_KEY");

    public RecoveryDecision decide(SignalBundle bundle) {
        if (apiKey == null || apiKey.isBlank()) return fallback(bundle, "Gemini key unavailable; conservative stop.");
        try {
            String body = "{\"systemInstruction\":{\"parts\":[{\"text\":\"Return only JSON with action, delay_hours, confidence, requires_human_approval, justification. Allowed actions: RETRY_SILENT, OFFER_ALT_METHOD, SEND_NUDGE, ESCALATE_HUMAN, STOP. Never recommend more than 3 total attempts.\"}]},\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"" + escape(bundle.toString()) + "\"}]}],\"generationConfig\":{\"responseMimeType\":\"application/json\"}}";
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT).header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(20)).build();
            String response = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
            return parse(response, bundle);
        } catch (Exception exception) {
            return fallback(bundle, "Gemini unavailable; conservative stop.");
        }
    }

    private RecoveryDecision parse(String response, SignalBundle bundle) {
        String action = value(response, "action", "STOP");
        int delay = integer(response, "delay_hours", bundle.customerOrderHistory().priorSameReasonRecoveredWithinHours().orElse(bundle.populationRecoveryPeakHours().orElse(36)));
        String confidence = value(response, "confidence", "low");
        String justification = value(response, "justification", "Gemini decision recorded.");
        RecoveryAction parsed = RecoveryAction.valueOf(action);
        boolean approval = parsed == RecoveryAction.OFFER_ALT_METHOD || parsed == RecoveryAction.SEND_NUDGE || parsed == RecoveryAction.ESCALATE_HUMAN;
        return new RecoveryDecision(parsed, Math.max(0, delay), confidence, approval, justification);
    }

    private RecoveryDecision fallback(SignalBundle bundle, String justification) {
        int delay = bundle.customerOrderHistory().priorSameReasonRecoveredWithinHours().orElse(bundle.populationRecoveryPeakHours().orElse(36));
        return new RecoveryDecision(RecoveryAction.STOP, delay, "low", false, justification);
    }
    private String value(String json, String name, String fallback) {
        Matcher matcher = Pattern.compile("\"" + name + "\"[ ]*:[ ]*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : fallback;
    }
    private int integer(String json, String name, int fallback) {
        Matcher matcher = Pattern.compile("\"" + name + "\"[ ]*:[ ]*([0-9]+)").matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }
    private String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
