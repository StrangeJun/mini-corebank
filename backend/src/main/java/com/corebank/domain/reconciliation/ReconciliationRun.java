package com.corebank.domain.reconciliation;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private ReconciliationTriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconciliationStatus status;

    @Column(name = "checked_accounts", nullable = false)
    private int checkedAccounts;

    @Column(name = "checked_transactions", nullable = false)
    private int checkedTransactions;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    @Column(name = "details_json", columnDefinition = "TEXT")
    private String detailsJson;

    @Column(name = "executed_by")
    private Long executedBy;

    @Column(name = "executed_at", nullable = false)
    private LocalDateTime executedAt;

    protected ReconciliationRun() {
    }

    public ReconciliationRun(ReconciliationTriggerType triggerType, ReconciliationStatus status,
                             int checkedAccounts, int checkedTransactions, int mismatchCount,
                             String detailsJson, Long executedBy, LocalDateTime executedAt) {
        this.triggerType = triggerType;
        this.status = status;
        this.checkedAccounts = checkedAccounts;
        this.checkedTransactions = checkedTransactions;
        this.mismatchCount = mismatchCount;
        this.detailsJson = detailsJson;
        this.executedBy = executedBy;
        this.executedAt = executedAt;
    }

    public Long getId() { return id; }
    public ReconciliationTriggerType getTriggerType() { return triggerType; }
    public ReconciliationStatus getStatus() { return status; }
    public int getCheckedAccounts() { return checkedAccounts; }
    public int getCheckedTransactions() { return checkedTransactions; }
    public int getMismatchCount() { return mismatchCount; }
    public String getDetailsJson() { return detailsJson; }
    public Long getExecutedBy() { return executedBy; }
    public LocalDateTime getExecutedAt() { return executedAt; }
}
