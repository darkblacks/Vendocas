package com.platform.shared.web;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIp {
    private ClientIp() {}

    /** Relies on the reverse proxy (Caddy) overwriting X-Forwarded-For; see server.forward-headers-strategy. */
    public static String of(HttpServletRequest req) {
        return req.getRemoteAddr();
    }
}
