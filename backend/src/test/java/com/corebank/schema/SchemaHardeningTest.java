package com.corebank.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Flyway V2: 고객 계좌 음수 잔액 CHECK 제약과 조회용 복합 인덱스. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SchemaHardeningTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void customerAccountCannotHaveNegativeBalance() {
        assertThatThrownBy(() -> insertAccount("110-999-000001", false, -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void systemAccountMayGoNegative() {
        insertAccount("SYSTEM-TEST", true, -1000);
        assertThat(jdbc.queryForObject("select balance from accounts where account_number = 'SYSTEM-TEST'", Long.class))
                .isEqualTo(-1000L);
    }

    @Test
    void compositeIndexesExist() {
        assertThat(indexExists("idx_transactions_from_status_type_created")).isTrue();
        assertThat(indexExists("idx_ledger_entries_account_created_id")).isTrue();
    }

    private void insertAccount(String number, boolean system, long balance) {
        jdbc.update("insert into accounts (account_number, user_id, is_system, balance, status, pin_hash, "
                + "created_at, updated_at) values (?, null, ?, ?, 'ACTIVE', 'x', now(), now())", number, system, balance);
    }

    private boolean indexExists(String name) {
        return jdbc.queryForObject("select count(*) from information_schema.indexes where lower(index_name) = ?",
                Integer.class, name) > 0;
    }
}
