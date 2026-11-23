package com.corebank.domain.transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /** 기간 내 해당 계좌에서 나간 완료 거래 합계 (1일 한도 계산용). */
    @Query("""
            select coalesce(sum(t.amount), 0) from Transaction t
            where t.fromAccountId = :accountId
              and t.status = :status
              and t.txType in :types
              and t.createdAt >= :from and t.createdAt < :to
            """)
    BigDecimal sumOutgoing(@Param("accountId") Long accountId,
                           @Param("status") TransactionStatus status,
                           @Param("types") Collection<TransactionType> types,
                           @Param("from") LocalDateTime from,
                           @Param("to") LocalDateTime to);
}
