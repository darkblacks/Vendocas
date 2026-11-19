package com.platform.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    @Query("select s from Subscription s where s.organizationId = ?1 and s.status not in (com.platform.billing.SubscriptionStatus.CANCELED, com.platform.billing.SubscriptionStatus.EXPIRED)")
    Optional<Subscription> findLive(UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.providerSubscriptionId = ?1")
    Optional<Subscription> lockByProviderId(String providerSubscriptionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.id = ?1")
    Optional<Subscription> lockById(UUID id);

    List<Subscription> findTop20ByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
}
