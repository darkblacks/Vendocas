package com.platform.shared.crypto;

import com.platform.shared.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM envelope for provider credentials, TOTP secrets and tracking secrets at rest.
 * Format: "v1:" + base64(iv(12) || ciphertext+tag). The key comes from the secret manager via
 * CREDENTIALS_ENCRYPTION_KEY and is never stored in the database or repository.
 */
@Component
public class SecretCipher {
    private static final String PREFIX = "v1:";
    private static final int IV_LEN = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(AppProperties props) {
        String b64 = props.security().credentialsEncryptionKey();
        if (b64 == null || b64.isBlank()) {
            throw new IllegalStateException("app.security.credentials-encryption-key (CREDENTIALS_ENCRYPTION_KEY) is required");
        }
        byte[] raw = Base64.getDecoder().decode(b64.trim());
        if (raw.length != 32) throw new IllegalStateException("CREDENTIALS_ENCRYPTION_KEY must be 32 bytes (base64)");
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(IV_LEN + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String envelope) {
        if (envelope == null || !envelope.startsWith(PREFIX)) throw new IllegalArgumentException("Unknown secret envelope");
        try {
            byte[] all = Base64.getDecoder().decode(envelope.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, IV_LEN));
            return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong key or tampered value)", e);
        }
    }
}
