package com.platform.support;

import com.platform.messaging.providers.MessageProvider;
import com.platform.taxation.FiscalDocumentProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only provider (registered ONLY in the test classpath) that records what would be sent.
 * Production code never contains a fake provider.
 */
@TestConfiguration
public class TestProviders {
    public static final List<MessageProvider.Outbound> SENT = new CopyOnWriteArrayList<>();
    public static volatile MessageProvider.Outcome nextOutcome = MessageProvider.Outcome.ACCEPTED;

    /** In-memory bucket: objects keyed exactly like production (org/{id}/...). */
    public static final java.util.Map<String, byte[]> BUCKET = new java.util.concurrent.ConcurrentHashMap<>();

    @Bean
    @Primary
    com.platform.storage.StorageService fakeStorage() {
        return new com.platform.storage.StorageService(null) {
            @Override public boolean configured() { return true; }
            @Override public void put(String key, byte[] content, String contentType) { BUCKET.put(key, content); }
            @Override public void delete(String key) { BUCKET.remove(key); }
            @Override public String presignedGet(String key, java.time.Duration ttl) { return "https://bucket.test/" + key + "?sig=x"; }
        };
    }

    @Bean
    MessageProvider fakeEmailProvider() {
        return new MessageProvider() {
            @Override public String provider() { return "FAKE"; }
            @Override public String channel() { return "EMAIL"; }
            @Override public boolean configured() { return true; }
            @Override public Result send(Outbound m, Connection c) {
                return switch (nextOutcome) {
                    case ACCEPTED -> { SENT.add(m); yield Result.accepted("fake-" + UUID.randomUUID()); }
                    case RETRYABLE -> Result.retryable("FAKE_503", "unavailable");
                    case PERMANENT -> Result.permanent("FAKE_400", "rejected");
                };
            }
        };
    }

    /** Fiscal provider double: records every call; never reaches Focus NFe. */
    public static final class FakeFiscal implements FiscalDocumentProvider {
        public final List<String> submitted = new CopyOnWriteArrayList<>();
        public volatile boolean configured = true;
        public volatile boolean fail = false;
        public volatile String outcome = "ISSUED";
        @Override public String name() { return "FOCUS_NFE"; }
        @Override public boolean configured() { return configured; }
        @Override public Status submit(NfseRequest r) {
            if (fail) throw new IllegalStateException("fiscal provider down");
            submitted.add(r.reference());
            return new Status(outcome, "ISSUED".equals(outcome) ? "NF-" + submitted.size() : null, null, null, null, null);
        }
        @Override public Status query(String reference) { return new Status(outcome, null, null, null, null, null); }
        public void reset() { submitted.clear(); configured = true; fail = false; outcome = "ISSUED"; }
    }

    public static final FakeFiscal FISCAL = new FakeFiscal();

    @Bean
    @Primary
    FiscalDocumentProvider fakeFiscalProvider() { return FISCAL; }
}
