package com.platform.security;

import com.platform.tenants.Role;

import java.util.UUID;

/** The resolved tenant for the current request. Every tenant query must filter by {@link #orgId()}. */
public record Tenant(UUID orgId, UUID userId, Role role) {
    public String actor() { return "user:" + userId; }
}
