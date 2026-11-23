package com.corebank.domain.account;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
public class Account {

    public static final BigDecimal DEFAULT_PER_TX_LIMIT = new BigDecimal("10000000");
    public static final BigDecimal DEFAULT_DAILY_LIMIT = new BigDecimal("50000000");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, unique = true, length = 30)
    private String accountNumber;

    /** 시스템 시재 계좌(SYSTEM-VAULT)는 null. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Column(nullable = false, precision = 19, scale = 0)
    private BigDecimal balance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "pin_hash", nullable = false, length = 255)
    private String pinHash;

    @Column(name = "pin_fail_count", nullable = false)
    private int pinFailCount;

    @Column(name = "per_tx_limit", nullable = false, precision = 19, scale = 0)
    private BigDecimal perTxLimit = DEFAULT_PER_TX_LIMIT;

    @Column(name = "daily_limit", nullable = false, precision = 19, scale = 0)
    private BigDecimal dailyLimit = DEFAULT_DAILY_LIMIT;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Account() {
    }

    public Account(String accountNumber, Long userId, boolean system, String pinHash) {
        this.accountNumber = accountNumber;
        this.userId = userId;
        this.system = system;
        this.pinHash = pinHash;
    }

    public Account(String accountNumber, Long userId, String pinHash, BigDecimal perTxLimit, BigDecimal dailyLimit) {
        this(accountNumber, userId, false, pinHash);
        this.perTxLimit = perTxLimit;
        this.dailyLimit = dailyLimit;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId != null && this.userId.equals(userId);
    }

    /** 차변 기입: 잔액 감소. 잔액 부족 검증은 서비스에서 한다 (SYSTEM-VAULT 는 음수가 될 수 있다). */
    public void debit(BigDecimal amount) {
        this.balance = this.balance.subtract(amount);
    }

    /** 대변 기입: 잔액 증가. */
    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    /** 상태 전환만 담당한다. 잔액·소유권 검증은 서비스에서 한다. */
    public void close() {
        this.status = AccountStatus.CLOSED;
    }

    /**
     * PIN 오류 1회 반영. 실패 횟수가 maxFailures 에 도달하면 LOCKED 로 전환한다.
     *
     * @return 이번 실패로 잠겼으면 true
     */
    public boolean recordPinFailure(int maxFailures) {
        this.pinFailCount++;
        if (this.pinFailCount >= maxFailures) {
            this.status = AccountStatus.LOCKED;
            return true;
        }
        return false;
    }

    public void resetPinFailures() {
        this.pinFailCount = 0;
    }

    /** 관리자 잠금 해제: ACTIVE 복귀 + PIN 실패 횟수 초기화. 해지 계좌 검증은 서비스에서 한다. */
    public void unlock() {
        this.status = AccountStatus.ACTIVE;
        this.pinFailCount = 0;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public Long getUserId() { return userId; }
    public boolean isSystem() { return system; }
    public BigDecimal getBalance() { return balance; }
    public AccountStatus getStatus() { return status; }
    public String getPinHash() { return pinHash; }
    public int getPinFailCount() { return pinFailCount; }
    public BigDecimal getPerTxLimit() { return perTxLimit; }
    public BigDecimal getDailyLimit() { return dailyLimit; }
    public Long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
