package com.platform.taxation;

/** Port for fiscal document issuance (e.g. Brazilian NFS-e). Distinct from billing invoices. */
public interface FiscalDocumentProvider {
    String name();

    boolean configured();

    record NfseRequest(String reference, String issuedAtIso, String sellerTaxId, String sellerMunicipalRegistration, String municipalityCode,
                       String serviceCode, String description, long amountMinor, String buyerTaxIdType, String buyerTaxId,
                       String buyerLegalName, String buyerEmail, String buyerAddressLine1, String buyerCity, String buyerState,
                       String buyerPostalCode) {}

    record Status(String status, String number, String verificationCode, String pdfUrl, String xmlUrl, String error) {}

    /** Idempotent by {@code reference}: re-submitting the same reference must not create a second document. */
    Status submit(NfseRequest request);

    Status query(String reference);
}
