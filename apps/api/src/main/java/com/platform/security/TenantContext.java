package com.platform.security;

import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;

public final class TenantContext {
    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static Tenant current() {
        Tenant t = CURRENT.get();
        if (t == null) throw new ApiException(ErrorCode.FORBIDDEN, "No organization context");
        return t;
    }

    static void set(Tenant t) { CURRENT.set(t); }

    static void clear() { CURRENT.remove(); }
}
