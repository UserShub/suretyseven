package com.suretyseven.repository;

import com.suretyseven.domain.NotificationOutbox;
import com.suretyseven.domain.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from NotificationOutbox o where o.status = :status and o.nextAttemptAt <= :now order by o.createdAt asc")
    List<NotificationOutbox> findDueForDelivery(@Param("status") OutboxStatus status,
                                                 @Param("now") Instant now,
                                                 Pageable pageable);
}
