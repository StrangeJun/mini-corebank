-- V1: 코어뱅킹 초기 스키마 (D-004 선택지 A, 8개 핵심 테이블)
-- 모든 금액 컬럼은 NUMERIC(19,0) (원 단위, scale 0)

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL
);

CREATE TABLE accounts (
    id             BIGSERIAL PRIMARY KEY,
    account_number VARCHAR(30)    NOT NULL UNIQUE,
    user_id        BIGINT REFERENCES users (id),
    is_system      BOOLEAN        NOT NULL DEFAULT FALSE,
    balance        NUMERIC(19, 0) NOT NULL DEFAULT 0,
    status         VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    pin_hash       VARCHAR(255)   NOT NULL,
    pin_fail_count INT            NOT NULL DEFAULT 0,
    per_tx_limit   NUMERIC(19, 0) NOT NULL DEFAULT 10000000,
    daily_limit    NUMERIC(19, 0) NOT NULL DEFAULT 50000000,
    version        BIGINT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMP      NOT NULL,
    updated_at     TIMESTAMP      NOT NULL
);

CREATE TABLE transactions (
    id              BIGSERIAL PRIMARY KEY,
    tx_type         VARCHAR(20)    NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    amount          NUMERIC(19, 0) NOT NULL CHECK (amount > 0),
    from_account_id BIGINT REFERENCES accounts (id),
    to_account_id   BIGINT REFERENCES accounts (id),
    idempotency_key VARCHAR(100) UNIQUE,
    description     VARCHAR(255),
    created_at      TIMESTAMP      NOT NULL
);

CREATE TABLE ledger_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT         NOT NULL REFERENCES transactions (id),
    account_id     BIGINT         NOT NULL REFERENCES accounts (id),
    entry_type     VARCHAR(10)    NOT NULL,
    amount         NUMERIC(19, 0) NOT NULL CHECK (amount > 0),
    balance_after  NUMERIC(19, 0) NOT NULL,
    created_at     TIMESTAMP      NOT NULL
);

CREATE TABLE idempotency_records (
    id              BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    user_id         BIGINT       NOT NULL,
    endpoint        VARCHAR(100) NOT NULL,
    request_hash    VARCHAR(128) NOT NULL,
    response_status INT          NOT NULL,
    response_body   TEXT         NOT NULL,
    created_at      TIMESTAMP    NOT NULL
);

CREATE TABLE scheduled_transfers (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT         NOT NULL REFERENCES users (id),
    from_account_id         BIGINT         NOT NULL REFERENCES accounts (id),
    to_account_id           BIGINT         NOT NULL REFERENCES accounts (id),
    amount                  NUMERIC(19, 0) NOT NULL CHECK (amount > 0),
    scheduled_at            TIMESTAMP      NOT NULL,
    status                  VARCHAR(20)    NOT NULL,
    failure_reason          VARCHAR(255),
    executed_transaction_id BIGINT REFERENCES transactions (id),
    idempotency_key         VARCHAR(100)   NOT NULL UNIQUE,
    created_at              TIMESTAMP      NOT NULL,
    executed_at             TIMESTAMP
);

CREATE TABLE reconciliation_runs (
    id                   BIGSERIAL PRIMARY KEY,
    trigger_type         VARCHAR(20) NOT NULL,
    status               VARCHAR(20) NOT NULL,
    checked_accounts     INT         NOT NULL,
    checked_transactions INT         NOT NULL,
    mismatch_count       INT         NOT NULL,
    details_json         TEXT,
    executed_by          BIGINT,
    executed_at          TIMESTAMP   NOT NULL
);

CREATE TABLE audit_logs (
    id             BIGSERIAL PRIMARY KEY,
    actor_user_id  BIGINT,
    action_type    VARCHAR(50) NOT NULL,
    target_type    VARCHAR(50) NOT NULL,
    target_id      VARCHAR(100),
    result         VARCHAR(20) NOT NULL,
    masked_summary TEXT        NOT NULL,
    client_ip      VARCHAR(64),
    created_at     TIMESTAMP   NOT NULL
);
