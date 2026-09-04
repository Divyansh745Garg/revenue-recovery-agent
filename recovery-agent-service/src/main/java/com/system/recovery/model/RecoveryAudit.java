package com.system.recovery.model;
import jakarta.persistence.*; import lombok.*; import java.time.LocalDateTime; import java.util.UUID;
@Entity @Getter @Setter @NoArgsConstructor
public class RecoveryAudit { @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id; private UUID orderId; @Column(length=8000) private String signalBundle; @Column(length=4000) private String decision; private String outcome; private LocalDateTime createdAt=LocalDateTime.now(); public RecoveryAudit(UUID o,String s,String d,String r){orderId=o;signalBundle=s;decision=d;outcome=r;} }
