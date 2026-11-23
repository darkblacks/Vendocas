package com.platform.operations;

/** Infers the Stripe mode from a secret/restricted key prefix without ever exposing the key. */
public final class StripeKeyMode {
    private StripeKeyMode() {}

    public enum Mode { TEST, LIVE, UNKNOWN, NONE }

    public static Mode of(String key) {
        if (key == null || key.isBlank()) return Mode.NONE;
        if (key.startsWith("sk_live_") || key.startsWith("rk_live_")) return Mode.LIVE;
        if (key.startsWith("sk_test_") || key.startsWith("rk_test_")) return Mode.TEST;
        return Mode.UNKNOWN;
    }
}
