package com.suretyseven.repository;

import com.suretyseven.domain.Application;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    Optional<Application> findByApplicationId(String applicationId);

    Optional<Application> findByIdempotencyKey(String idempotencyKey);

    /**
     * Ownership-scoped lookup: returns empty both when the ID doesn't exist
     * AND when it exists but belongs to a different broker -- the caller
     * (ApplicationService) can't and shouldn't distinguish the two, since
     * doing so would confirm a competitor's applicationId exists.
     */
    Optional<Application> findByApplicationIdAndBrokerId(String applicationId, String brokerId);

    Page<Application> findAllByBrokerId(String brokerId, Pageable pageable);
}
