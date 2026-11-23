package com.platform.operations;

import java.time.Instant;

/**
 * Independent platform switches. None implies another: homologation may use Stripe LIVE (real-money integration
 * tests), production may run with fiscal automation off, and the fiscal provider can be chosen before automation is on.
 */
public record PlatformSettings(Environment environment, StripeMode stripeMode, boolean fiscalAutomation, FiscalProvider fiscalProvider,
                               int version, String updatedBy, Instant updatedAt) {

    public enum Environment { HOMOLOGATION, PRODUCTION }

    public enum StripeMode { TEST, LIVE }

    public enum FiscalProvider { NONE, FOCUS_NFE }

    /** Fiscal documents are issued automatically only with automation ON and a provider selected. */
    public boolean automaticIssuance() { return fiscalAutomation && fiscalProvider != FiscalProvider.NONE; }
}
