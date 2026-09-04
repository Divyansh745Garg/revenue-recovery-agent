package com.system.recovery.dto;

import com.system.recovery.model.RecoveryAction;

public record RecoveryDecision(RecoveryAction action, int delayHours, String confidence,
                               boolean requiresHumanApproval, String justification) {}
