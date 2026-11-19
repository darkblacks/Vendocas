package com.platform.tenants;

import java.util.EnumSet;
import java.util.Set;

import static com.platform.tenants.Permission.*;

/** Closed role set. Mirrored in table role_permissions (V8) for audit/admin display. */
public enum Role {
    OWNER(EnumSet.allOf(Permission.class)),
    ADMIN(EnumSet.complementOf(EnumSet.of(BILLING_MANAGE))),
    FINANCE(EnumSet.of(ORG_READ, BILLING_READ, BILLING_MANAGE, REPORTS_READ, AUDIT_READ)),
    OPERATOR(EnumSet.of(ORG_READ, CONTACTS_READ, CONTACTS_WRITE, CONSENT_MANAGE, CHANNELS_READ, AUTOMATIONS_READ,
            AUTOMATIONS_WRITE, CAMPAIGNS_READ, CAMPAIGNS_WRITE, CONVERSATIONS_READ, CONVERSATIONS_REPLY, CALLS_READ,
            CALLS_MANAGE, REPORTS_READ)),
    READ_ONLY(EnumSet.of(ORG_READ, CONTACTS_READ, CHANNELS_READ, AUTOMATIONS_READ, CAMPAIGNS_READ, CONVERSATIONS_READ,
            CALLS_READ, REPORTS_READ));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) { this.permissions = permissions; }

    public boolean has(Permission p) { return permissions.contains(p); }

    public Set<Permission> permissions() { return permissions; }

    public boolean isAdministrative() { return this == OWNER || this == ADMIN; }
}
