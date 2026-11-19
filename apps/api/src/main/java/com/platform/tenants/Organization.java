package com.platform.tenants;

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
@Table(name = "organizations")
@Getter
@Setter
public class Organization {
    @Id
    private UUID id;
    private String legalName;
    private String tradeName;
    private String countryCode;
    private String billingCountryCode;
    private String billingCurrency;
    private String locale;
    private String timeZone;
    private String dataRegion;
    @Column(columnDefinition = "citext")
    private String contactEmail;
    @Column(columnDefinition = "citext")
    private String financeEmail;
    private String contactPhone;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String region;
    private String postalCode;
    private String logoObjectKey;
    private String status;
    private Instant termsAcceptedAt;
    private String termsVersion;
    private String publicKey;
    private String trackingSecretEnc;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private long version;
}
