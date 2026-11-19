package com.platform.unit;

import com.platform.shared.AppProperties;
import com.platform.shared.crypto.SecretCipher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityPrimitivesTest {

    @Test
    void totpMatchesRfc6238Vector() throws Exception {
        // RFC 6238 SHA1 secret "12345678901234567890"; T=59s → 94287082 (last 6 digits 287082).
        Method gen = Class.forName("com.platform.identity.Totp").getDeclaredMethod("generate", String.class, long.class);
        gen.setAccessible(true);
        assertThat(gen.invoke(null, "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59L / 30)).isEqualTo("287082");
        assertThat(com.platform.identity.Totp.verify("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287082", Instant.ofEpochSecond(59))).isTrue();
        assertThat(com.platform.identity.Totp.verify("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "000000", Instant.ofEpochSecond(59))).isFalse();
    }

    @Test
    void secretCipherRoundTripAndTamperDetection() {
        var props = new AppProperties(null, null, null, new AppProperties.Security(null, "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", null, null, null, true),
                null, null, null, null, null, null, null, null, null);
        SecretCipher c = new SecretCipher(props);
        String enc = c.encrypt("EAAG-token");
        assertThat(enc).startsWith("v1:").doesNotContain("EAAG");
        assertThat(c.decrypt(enc)).isEqualTo("EAAG-token");
        String tampered = enc.substring(0, enc.length() - 4) + (enc.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThatThrownBy(() -> c.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void uploadSniffingRejectsDisguisedFiles() throws Exception {
        Method sniff = Class.forName("com.platform.storage.MediaController").getDeclaredMethod("sniff", byte[].class);
        sniff.setAccessible(true);
        assertThat(sniff.invoke(null, (Object) "<html><script>alert(1)</script>".getBytes())).isNull();
        assertThat(sniff.invoke(null, (Object) new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0})).isEqualTo("image/png");
        assertThat(sniff.invoke(null, (Object) new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("image/jpeg");
    }
}
