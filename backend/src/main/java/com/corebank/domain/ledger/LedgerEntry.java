package com.corebank.domain.ledger;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 복식부기 분개. Append-Only: 생성 후 수정하지 않는다. */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private Long transactionId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10, updatable = false)
    private EntryType entryType;

    @Column(nullable = false, precision = 19, scale = 0, updatable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 0, updatable = false)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(Long transactionId, Long accountId, EntryType entryType,
                       BigDecimal amount, BigDecimal balanceAfter) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getTransactionId() { return transactionId; }
    public Long getAccountId() { return accountId; }
    public EntryType getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
