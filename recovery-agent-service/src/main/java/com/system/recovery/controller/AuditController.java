package com.system.recovery.controller;
import com.system.recovery.model.RecoveryAudit; import com.system.recovery.repository.RecoveryAuditRepository; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestController @RequestMapping("/api/v1/audits") public class AuditController { private final RecoveryAuditRepository r; public AuditController(RecoveryAuditRepository r){this.r=r;} @GetMapping public List<RecoveryAudit> all(){return r.findAll().stream().sorted(Comparator.comparing(RecoveryAudit::getCreatedAt).reversed()).limit(50).toList();} }
