-- V2: M1 리뷰(RV-M1-04, RV-M1-06) 반영 — DB 레벨 무결성 제약과 조회용 복합 인덱스

-- 고객 계좌는 음수 잔액 불가. SYSTEM-VAULT 등 시스템 계좌는 복식부기 상대 계정이라 음수를 허용한다.
ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_non_negative_customer_balance CHECK (is_system = TRUE OR balance >= 0);

-- 1일 한도 계산 (TransactionRepository.sumOutgoing)
CREATE INDEX idx_transactions_from_status_type_created
    ON transactions (from_account_id, status, tx_type, created_at);

-- 계좌별 거래내역 최신순 페이징 (TransactionHistoryService)
CREATE INDEX idx_ledger_entries_account_created_id
    ON ledger_entries (account_id, created_at DESC, id DESC);

-- 대사 거래별 차변/대변 검사 조인 (ReconciliationChecker). PostgreSQL 은 FK 에 인덱스를 자동 생성하지 않는다.
CREATE INDEX idx_ledger_entries_transaction_id
    ON ledger_entries (transaction_id);

-- 도래 예약 이체 조회 (ScheduledTransferRepository.findDueIds: status = PENDING, scheduled_at <= now, 정렬 scheduled_at, id)
CREATE INDEX idx_scheduled_transfers_status_scheduled_id
    ON scheduled_transfers (status, scheduled_at, id);
