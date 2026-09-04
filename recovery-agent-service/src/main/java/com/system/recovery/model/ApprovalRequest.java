package com.system.recovery.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Getter @Setter @NoArgsConstructor
public class ApprovalRequest { @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id; private UUID orderId; @Enumerated(EnumType.STRING) private RecoveryAction action; private String decisionJson; private String status; private LocalDateTime createdAt=LocalDateTime.now(); public ApprovalRequest(UUID o, RecoveryAction a,String d){orderId=o;action=a;decisionJson=d;status="PENDING";} }
