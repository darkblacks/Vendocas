package com.platform.identity;

import com.platform.shared.crypto.Tokens;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;

/** RFC 6238 TOTP (HMAC-SHA1, 30s step, 6 digits) — compatible with standard authenticator apps. */
public final class Totp {
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    public static String newSecret() {
        byte[] b = new byte[20];
        RANDOM.nextBytes(b);
        return base32(b);
    }

    public static boolean verify(String base32Secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{6}")) return false;
        long step = now.getEpochSecond() / 30;
        for (long w = -1; w <= 1; w++) {
            if (Tokens.constantTimeEquals(generate(base32Secret, step + w), code)) return true;
        }
        return false;
    }

    static String generate(String base32Secret, long counter) {
        byte[] hash = Tokens.hmac("HmacSHA1", decode(base32Secret), ByteBuffer.allocate(8).putLong(counter).array());
        int offset = hash[hash.length - 1] & 0xF;
        int bin = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16) | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
        return String.format("%06d", bin % 1_000_000);
    }

    public static String otpauthUri(String issuer, String account, String secret) {
        String enc = java.net.URLEncoder.encode(issuer + ":" + account, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + enc + "?secret=" + secret + "&issuer=" + java.net.URLEncoder.encode(issuer, java.nio.charset.StandardCharsets.UTF_8) + "&algorithm=SHA1&digits=6&period=30";
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(B32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(B32.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] decode(String s) {
        String clean = s.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : clean.toCharArray()) {
            int v = B32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Invalid base32");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xFF));
                bits -= 8;
            }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }
}
