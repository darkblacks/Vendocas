package com.platform.channels;

import com.platform.shared.AppProperties;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

import java.util.*;

/**
 * Email channel: (a) the organization's own domain verified in SES with Easy DKIM, or (b) a
 * platform-managed sender on our verified domain with Reply-To set to the organization.
 * No customer SMTP passwords are ever requested or stored.
 */
@Service
public class EmailChannelSetup {
    private final ObjectProvider<SesV2Client> ses;
    private final ChannelService channels;
    private final AppProperties props;
    private final JdbcTemplate jdbc;

    public EmailChannelSetup(ObjectProvider<SesV2Client> ses, ChannelService channels, AppProperties props, JdbcTemplate jdbc) {
        this.ses = ses;
        this.channels = channels;
        this.props = props;
        this.jdbc = jdbc;
    }

    private SesV2Client client() {
        SesV2Client c = ses.getIfAvailable();
        if (c == null) throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED, "Email provider (Amazon SES) is not configured on this platform");
        return c;
    }

    @Transactional
    public void startDomain(UUID orgId, String domain, String localPart, String displayName) {
        String d = domain.trim().toLowerCase(Locale.ROOT);
        if (!d.matches("^(?=.{4,253}$)([a-z0-9-]{1,63}\\.)+[a-z]{2,63}$")) throw ApiException.badRequest("Invalid domain");
        if (!localPart.matches("^[a-zA-Z0-9._%+-]{1,64}$")) throw ApiException.badRequest("Invalid sender address");
        List<String> tokens;
        try {
            tokens = client().createEmailIdentity(CreateEmailIdentityRequest.builder().emailIdentity(d)
                    .tags(Tag.builder().key("organization_id").value(orgId.toString()).build()).build()).dkimAttributes().tokens();
        } catch (AlreadyExistsException e) {
            // The identity might belong to another tenant: only proceed if it is ours.
            Integer mine = jdbc.queryForObject("SELECT count(*) FROM sender_identities WHERE organization_id = ? AND kind = 'EMAIL_DOMAIN' AND value = ?", Integer.class, orgId, d);
            if (mine == null || mine == 0) throw ApiException.conflict("This domain is already registered by another account");
            tokens = client().getEmailIdentity(GetEmailIdentityRequest.builder().emailIdentity(d).build()).dkimAttributes().tokens();
        }
        List<Map<String, String>> records = new ArrayList<>();
        for (String t : tokens) records.add(Map.of("type", "CNAME", "name", t + "._domainkey." + d, "value", t + ".dkim.amazonses.com"));
        records.add(Map.of("type", "TXT", "name", "_dmarc." + d, "value", "v=DMARC1; p=none; rua=mailto:dmarc@" + d, "note", "recommended"));
        UUID conn = channels.upsert(orgId, "EMAIL", "SES", "PENDING_VERIFICATION", "Waiting for DNS records to propagate",
                "Publish the DNS records shown and click Verify", displayName, d, Map.of("mode", "OWN_DOMAIN", "localPart", localPart));
        channels.upsertSender(orgId, conn, "EMAIL_DOMAIN", d, displayName, "PENDING_VERIFICATION", Map.of("dnsRecords", records), null, false);
        channels.upsertSender(orgId, conn, "EMAIL_ADDRESS", localPart + "@" + d, displayName, "PENDING_VERIFICATION", Map.of(), null, true);
    }

    /** Polls SES; promotes to ACTIVE only when SES reports the identity verified for sending. */
    @Transactional
    public String refresh(UUID orgId) {
        var c = channels.find(orgId, "EMAIL").orElseThrow(() -> ApiException.notFound("Email channel"));
        if (!"OWN_DOMAIN".equals(c.config().path("mode").asString())) return c.status();
        var res = client().getEmailIdentity(GetEmailIdentityRequest.builder().emailIdentity(c.externalAccountId()).build());
        boolean ok = Boolean.TRUE.equals(res.verifiedForSendingStatus());
        String dkim = res.dkimAttributes() == null ? "UNKNOWN" : String.valueOf(res.dkimAttributes().status());
        if (ok) {
            channels.setStatus(c.id(), "ACTIVE", "Domain verified (DKIM " + dkim + ")", null);
            jdbc.update("UPDATE sender_identities SET status = 'ACTIVE', updated_at = now() WHERE connection_id = ?", c.id());
            return "ACTIVE";
        }
        String status = "FAILED".equals(dkim) ? "ERROR" : "PENDING_VERIFICATION";
        channels.setStatus(c.id(), status, "DKIM status: " + dkim, "Check the DNS records at your DNS provider");
        return status;
    }

    /** Platform-managed sender (customers without their own domain). */
    @Transactional
    public void usePlatformSender(UUID orgId, String displayName) {
        String domain = props.mail().platformDomain();
        if (domain == null || domain.isBlank()) throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED, "Platform email domain not configured");
        var identity = client().getEmailIdentity(GetEmailIdentityRequest.builder().emailIdentity(domain).build());
        if (!Boolean.TRUE.equals(identity.verifiedForSendingStatus())) {
            throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED, "Platform sending domain is not verified in SES");
        }
        var org = jdbc.queryForMap("SELECT trade_name, contact_email, public_key FROM organizations WHERE id = ?", orgId);
        String slug = ((String) org.get("trade_name")).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) slug = "store";
        String local = (slug.length() > 30 ? slug.substring(0, 30) : slug) + "-" + ((String) org.get("public_key")).substring(3, 9).toLowerCase(Locale.ROOT);
        UUID conn = channels.upsert(orgId, "EMAIL", "SES", "ACTIVE", "Platform-managed sender", null, displayName, domain,
                Map.of("mode", "PLATFORM", "replyTo", org.get("contact_email")));
        channels.upsertSender(orgId, conn, "PLATFORM_SUBDOMAIN", local + "@" + domain, displayName, "ACTIVE", Map.of(), null, true);
        channels.setStatus(conn, "ACTIVE", "Platform-managed sender " + local + "@" + domain, null);
    }
}
