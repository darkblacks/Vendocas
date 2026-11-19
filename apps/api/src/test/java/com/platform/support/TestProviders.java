package com.platform.support;

import com.platform.messaging.providers.MessageProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

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
}
