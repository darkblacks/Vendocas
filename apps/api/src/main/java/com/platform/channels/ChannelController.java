package com.platform.channels;

import com.platform.billing.EntitlementService;
import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.api.ApiException;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/channels")
@Tag(name = "channels")
public class ChannelController {
    private final ChannelService channels;
    private final EmailChannelSetup email;
    private final WhatsAppChannelSetup whatsapp;
    private final SmsChannelSetup sms;
    private final EntitlementService entitlements;

    public ChannelController(ChannelService channels, EmailChannelSetup email, WhatsAppChannelSetup whatsapp, SmsChannelSetup sms,
                             EntitlementService entitlements) {
        this.channels = channels;
        this.email = email;
        this.whatsapp = whatsapp;
        this.sms = sms;
        this.entitlements = entitlements;
    }

    @GetMapping
    @RequiresPermission(Permission.CHANNELS_READ)
    public List<ChannelService.ChannelView> list() { return channels.overview(TenantContext.current().orgId()); }

    public record DomainRequest(@NotBlank String domain, @NotBlank String localPart, @NotBlank @Size(max = 120) String displayName) {}

    @PostMapping("/email/domain")
    @RequiresPermission(Permission.CHANNELS_MANAGE)
    public Map<String, String> emailDomain(@Valid @RequestBody DomainRequest r) {
        var org = TenantContext.current().orgId();
        entitlements.require(org, EntitlementService.CHANNEL_EMAIL);
        email.startDomain(org, r.domain(), r.localPart(), r.displayName());
        return Map.of("status", "PENDING_VERIFICATION");
    }

    public record DisplayNameRequest(@NotBlank @Size(max = 120) String displayName) {}

    @PostMapping("/email/platform-sender")
    @RequiresPermission(Permission.CHANNELS_MANAGE)
    public Map<String, String> emailPlatform(@Valid @RequestBody DisplayNameRequest r) {
        var org = TenantContext.current().orgId();
        entitlements.require(org, EntitlementService.CHANNEL_EMAIL);
        email.usePlatformSender(org, r.displayName());
        return Map.of("status", "ACTIVE");
    }

    @PostMapping("/{channel}/refresh")
    @RequiresPermission(Permission.CHANNELS_MANAGE)
    public Map<String, String> refresh(@PathVariable String channel) {
        var org = TenantContext.current().orgId();
        return Map.of("status", switch (channel.toUpperCase()) {
            case "EMAIL" -> email.refresh(org);
            case "WHATSAPP" -> whatsapp.refresh(org);
            default -> throw ApiException.badRequest("Refresh not supported for " + channel);
        });
    }

    @GetMapping("/whatsapp/signup-config")
    @RequiresPermission(Permission.CHANNELS_MANAGE)
    public WhatsAppChannelSetup.SignupConfig waConfig() { return whatsapp.config(); }

    @PostMapping("/whatsapp/embedded-signup")
    @RequiresPermission(Permission.CHANNELS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void waSignup(@RequestBody WhatsAppChannelSetup.CompleteRequest req) {
        var t = TenantContext.current();
        entitlements.require(t.orgId(), EntitlementService.CHANNEL_WHATSAPP);
        whatsapp.complete(t.orgId(), t.userId(), req);
    }

    @GetMapping("/sms/available-numbers")
    @RequiresPermission(Permission.CHANNELS_MANAGE)
    public List<SmsChannelSetup.AvailableNumber> numbers(@RequestParam String country, @RequestParam(required = false) String areaCode) {
        var org = TenantContext.current().orgId();
        entitlements.require(org, EntitlementService.CHANNEL_SMS);
        return sms.search(org, country.toUpperCase(), areaCode);
    }

    public record PurchaseRequest(@NotBlank String phoneNumber) {}

    @PostMapping("/sms/numbers")
    @RequiresPermission(Permission.CHANNELS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purchase(@Valid @RequestBody PurchaseRequest r) {
        var org = TenantContext.current().orgId();
        entitlements.require(org, EntitlementService.CHANNEL_SMS);
        sms.purchase(org, r.phoneNumber());
    }

    public record A2pRequest(@NotBlank String messagingServiceSid) {}

    @PostMapping("/sms/a2p/refresh")
    @RequiresPermission(Permission.CHANNELS_MANAGE)
    public Map<String, String> a2p(@Valid @RequestBody A2pRequest r) {
        return Map.of("status", sms.refreshA2p(TenantContext.current().orgId(), r.messagingServiceSid()));
    }

    @DeleteMapping("/{channel}")
    @RequiresPermission(Permission.CHANNELS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable String channel) { channels.disconnect(TenantContext.current().orgId(), channel.toUpperCase()); }
}
