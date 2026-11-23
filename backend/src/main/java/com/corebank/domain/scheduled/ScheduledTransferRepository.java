package com.corebank.domain.scheduled;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduledTransferRepository extends JpaRepository<ScheduledTransfer, Long> {

    /**
     * 도래 건 id 만 조회한다 (엔티티 미적재 — 실행 시 행 잠금으로 다시 읽는다). pageable 로 1회 처리량을 제한한다.
     * idx_scheduled_transfers_status_scheduled_id (status, scheduled_at, id) 를 탄다.
     */
    @Query("""
            select s.id from ScheduledTransfer s
            where s.status = :status and s.scheduledAt <= :now
            order by s.scheduledAt asc, s.id asc
            """)
    List<Long> findDueIds(@Param("status") ScheduledTransferStatus status, @Param("now") LocalDateTime now,
                          Pageable pageable);

    /** 실행·취소 전 행 잠금 (SELECT ... FOR UPDATE). 동시 스케줄러의 중복 실행을 막는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScheduledTransfer s where s.id = :id")
    Optional<ScheduledTransfer> findByIdForUpdate(@Param("id") Long id);

    Page<ScheduledTransfer> findByUserId(Long userId, Pageable pageable);

    Page<ScheduledTransfer> findByUserIdAndStatus(Long userId, ScheduledTransferStatus status, Pageable pageable);
}
