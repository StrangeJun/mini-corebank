package com.corebank.domain.scheduled;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface ScheduledTransferRepository extends JpaRepository<ScheduledTransfer, Long> {

    List<ScheduledTransfer> findByStatusAndScheduledAtLessThanEqual(ScheduledTransferStatus status, LocalDateTime now);

    List<ScheduledTransfer> findByUserId(Long userId);
}
