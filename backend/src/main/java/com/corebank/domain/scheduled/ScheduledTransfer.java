package com.corebank.domain.scheduled;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "scheduled_transfers")
public class ScheduledTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "from_account_id", nullable = false)
    private Long fromAccountId;

    @Column(name = "to_account_id", nullable = false)
    private Long toAccountId;

    @Column(nullable = false, precision = 19, scale = 0)
    private BigDecimal amount;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduledTransferStatus status = ScheduledTransferStatus.PENDING;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "executed_transaction_id")
    private Long executedTransactionId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    protected ScheduledTransfer() {
    }

    public ScheduledTransfer(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount,
                             LocalDateTime scheduledAt, String idempotencyKey) {
        this.userId = userId;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.scheduledAt = scheduledAt;
        this.idempotencyKey = idempotencyKey;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public void complete(Long transactionId, LocalDateTime at) {
        this.status = ScheduledTransferStatus.COMPLETED;
        this.executedTransactionId = transactionId;
        this.executedAt = at;
    }

    public void fail(String reason, LocalDateTime at) {
        this.status = ScheduledTransferStatus.FAILED;
        this.failureReason = reason;
        this.executedAt = at;
    }

    public void cancel() {
        this.status = ScheduledTransferStatus.CANCELED;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getFromAccountId() { return fromAccountId; }
    public Long getToAccountId() { return toAccountId; }
    public BigDecimal getAmount() { return amount; }
    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public ScheduledTransferStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Long getExecutedTransactionId() { return executedTransactionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExecutedAt() { return executedAt; }
}
