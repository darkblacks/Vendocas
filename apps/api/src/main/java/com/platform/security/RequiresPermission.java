package com.platform.security;

import com.platform.tenants.Permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tenant-scoped endpoint. The request must carry {@code X-Organization-Id}; the caller
 * must be an active member of that organization whose role grants the permission.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    Permission value();
}
