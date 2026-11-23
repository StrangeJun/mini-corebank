package com.corebank.domain.audit;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 감사 로그. Append-Only: 생성 후 수정하지 않는다. masked_summary 에는 마스킹된 값만 저장한다. */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id", updatable = false)
    private Long actorUserId;

    @Column(name = "action_type", nullable = false, length = 50, updatable = false)
    private String actionType;

    @Column(name = "target_type", nullable = false, length = 50, updatable = false)
    private String targetType;

    @Column(name = "target_id", length = 100, updatable = false)
    private String targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private AuditResult result;

    @Column(name = "masked_summary", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String maskedSummary;

    @Column(name = "client_ip", length = 64, updatable = false)
    private String clientIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected AuditLog() {
    }

    public AuditLog(Long actorUserId, String actionType, String targetType, String targetId,
                    AuditResult result, String maskedSummary, String clientIp) {
        this.actorUserId = actorUserId;
        this.actionType = actionType;
        this.targetType = targetType;
        this.targetId = targetId;
        this.result = result;
        this.maskedSummary = maskedSummary;
        this.clientIp = clientIp;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getActorUserId() { return actorUserId; }
    public String getActionType() { return actionType; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public AuditResult getResult() { return result; }
    public String getMaskedSummary() { return maskedSummary; }
    public String getClientIp() { return clientIp; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
