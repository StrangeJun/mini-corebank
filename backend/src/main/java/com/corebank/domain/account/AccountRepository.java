package com.corebank.domain.account;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    List<Account> findByUserId(Long userId);

    List<Account> findByUserIdAndSystemFalseOrderByCreatedAtAscIdAsc(Long userId);

    /**
     * 엔티티를 1차 캐시에 올리지 않고 id 만 조회한다.
     * (먼저 엔티티를 읽어두면 이후 FOR UPDATE 조회가 캐시된 옛 값을 돌려줄 수 있다.)
     */
    @Query("select a.id from Account a where a.accountNumber = :accountNumber")
    Optional<Long> findIdByAccountNumber(@Param("accountNumber") String accountNumber);

    /** pin_hash 만 조회한다 (엔티티 미적재). */
    @Query("select a.pinHash from Account a where a.id = :id")
    Optional<String> findPinHashById(@Param("id") Long id);

    /** 잔액·상태 변경 전 행 잠금 (SELECT ... FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
