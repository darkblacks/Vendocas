package com.platform.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscriptions")
@Getter
@Setter
public class Subscription {
    @Id
    private UUID id;
    private UUID organizationId;
    private UUID planId;
    private UUID priceId;
    @Enumerated(EnumType.STRING)
    private SubscriptionStatus status;
    private String collectionMethod;
    private String provider;
    private String providerSubscriptionId;
    private String providerCheckoutId;
    private Instant currentPeriodStart;
    private Instant currentPeriodEnd;
    private Instant graceUntil;
    private boolean cancelAtPeriodEnd;
    private Instant canceledAt;
    private UUID pendingPlanId;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private long version;
}
