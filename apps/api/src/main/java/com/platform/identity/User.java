package com.platform.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {
    @Id
    private UUID id;
    @Column(columnDefinition = "citext")
    private String email;
    private String fullName;
    private String passwordHash;
    private Instant emailVerifiedAt;
    private String locale;
    private String timeZone;
    private String mfaSecretEnc;
    private Instant mfaEnabledAt;
    private String platformRole;
    private int failedLogins;
    private Instant lockedUntil;
    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private long version;

    public boolean mfaEnabled() { return mfaEnabledAt != null; }
}
