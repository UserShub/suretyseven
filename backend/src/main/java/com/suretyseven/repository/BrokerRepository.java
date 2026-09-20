package com.suretyseven.repository;

import com.suretyseven.domain.Broker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BrokerRepository extends JpaRepository<Broker, Long> {
    Optional<Broker> findByClientId(String clientId);
}
