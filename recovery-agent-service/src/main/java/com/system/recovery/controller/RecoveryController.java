package com.system.recovery.controller;

import com.system.recovery.dto.PaymentFailedEvent;
import com.system.recovery.service.RecoveryDecisionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/recovery")
public class RecoveryController {
    private final RecoveryDecisionService decisions;
    public RecoveryController(RecoveryDecisionService decisions) { this.decisions = decisions; }
    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluate(@RequestBody PaymentFailedEvent event) { return ResponseEntity.ok(decisions.decide(event)); }
}
