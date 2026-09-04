package com.system.recovery.service;

import com.system.recovery.dto.RecoveryDecision;
import com.system.recovery.dto.SignalBundle;
import com.system.recovery.model.RecoveryAction;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Gemini is advisory only; this class never exposes executable tools to the model. */
@Component
@Slf4j
public class GeminiDecisionClient {
    private static final URI ENDPOINT = URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey = System.getenv("GEMINI_API_KEY");
    private final boolean mockMode = Boolean.parseBoolean(System.getenv().getOrDefault("LLM_MOCK_MODE", "false"));

    public RecoveryDecision decide(SignalBundle bundle) {
        if (mockMode) return mockDecision(bundle);
        if (apiKey == null || apiKey.isBlank()) return fallback(bundle, "Gemini key unavailable; conservative stop.");
        try {
            String body = "{\"systemInstruction\":{\"parts\":[{\"text\":\"Return only JSON with action, delay_hours, confidence, requires_human_approval, justification. Allowed actions: RETRY_SILENT, OFFER_ALT_METHOD, SEND_NUDGE, ESCALATE_HUMAN, STOP. Never recommend more than 3 total attempts.\"}]},\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"" + escape(bundle.toString()) + "\"}]}],\"generationConfig\":{\"responseMimeType\":\"application/json\"}}";
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT).header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(20)).build();
            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() / 100 != 2) {
                log.error("Gemini decision request failed with HTTP status {}", httpResponse.statusCode());
                throw new IllegalStateException("Gemini returned HTTP " + httpResponse.statusCode());
            }
            String response = httpResponse.body();
            cacheSuccessfulResponse(bundle, response);
            return parse(response, bundle);
        } catch (Exception exception) {
            log.error("Gemini decision request failed: {}", exception.toString());
            return fallback(bundle, "Gemini unavailable; conservative stop.");
        }
    }

    private void cacheSuccessfulResponse(SignalBundle bundle, String response) {
        try {
            String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bundle.toString().getBytes(StandardCharsets.UTF_8)));
            Path cache = Path.of("cached_llm_responses.json");
            String entry = "{\"input_hash\":\"" + hash + "\",\"raw_response\":" + response + "}";
            String prior = Files.exists(cache) ? Files.readString(cache) : "[]";
            String updated = prior.equals("[]") ? "[" + entry + "]" : prior.substring(0, prior.length() - 1) + "," + entry + "]";
            Files.writeString(cache, updated, StandardCharsets.UTF_8);
        } catch (Exception exception) { log.warn("Unable to cache Gemini response: {}", exception.toString()); }
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

    private RecoveryDecision mockDecision(SignalBundle bundle) {
        if (bundle.attemptNumber() >= 3 || bundle.customerOrderHistory().totalPriorOrders() == 0 && "DO_NOT_HONOR".equals(bundle.declineReason())) {
            return new RecoveryDecision(RecoveryAction.ESCALATE_HUMAN, 0, "high", true, "Mock: repeated decline with no customer history requires review.");
        }
        int delay = bundle.customerOrderHistory().priorSameReasonRecoveredWithinHours().orElse(bundle.populationRecoveryPeakHours().orElse(36));
        return new RecoveryDecision(RecoveryAction.RETRY_SILENT, delay, "high", false, "Mock: prior successful history supports a silent retry.");
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
