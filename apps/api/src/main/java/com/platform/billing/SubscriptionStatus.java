package com.platform.billing;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/** Explicit subscription state machine. Any transition not listed here is rejected. */
public enum SubscriptionStatus {
    DRAFT, CHECKOUT_PENDING, PAYMENT_PENDING, ACTIVE, PAST_DUE, GRACE_PERIOD, SUSPENDED, CANCELED, EXPIRED;

    private static final Map<SubscriptionStatus, Set<SubscriptionStatus>> ALLOWED = Map.ofEntries(
            entry(DRAFT, EnumSet.of(CHECKOUT_PENDING, PAYMENT_PENDING, CANCELED)),
            entry(CHECKOUT_PENDING, EnumSet.of(ACTIVE, PAYMENT_PENDING, EXPIRED, CANCELED)),
            entry(PAYMENT_PENDING, EnumSet.of(ACTIVE, PAST_DUE, EXPIRED, CANCELED)),
            entry(ACTIVE, EnumSet.of(ACTIVE, PAST_DUE, CANCELED)),
            entry(PAST_DUE, EnumSet.of(ACTIVE, GRACE_PERIOD, SUSPENDED, CANCELED)),
            entry(GRACE_PERIOD, EnumSet.of(ACTIVE, SUSPENDED, CANCELED)),
            entry(SUSPENDED, EnumSet.of(ACTIVE, CANCELED, EXPIRED)),
            entry(CANCELED, EnumSet.noneOf(SubscriptionStatus.class)),
            entry(EXPIRED, EnumSet.noneOf(SubscriptionStatus.class)));

    public boolean canTransitionTo(SubscriptionStatus next) { return ALLOWED.get(this).contains(next); }

    /** Paid features (including sending) are available while the customer is in good standing or tolerance. */
    public boolean entitlesService() { return this == ACTIVE || this == PAST_DUE || this == GRACE_PERIOD; }

    public boolean isTerminal() { return this == CANCELED || this == EXPIRED; }
}
