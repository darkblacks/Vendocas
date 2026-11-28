package com.platform.storage;

import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.shared.crypto.Tokens;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Stores files of a store in the private bucket under the store's own prefix ({@code org/{organizationId}/...}), so each
 * customer has its own set of images. Files are validated by magic bytes (never by name or declared type), limited to
 * 5 MB and decoded to prove they are real images. Reading is only through short-lived presigned URLs.
 */
@Service
public class MediaService {
    public static final long MAX_BYTES = 5 * 1024 * 1024;
    private final StorageService storage;
    private final JdbcTemplate jdbc;

    public MediaService(StorageService storage, JdbcTemplate jdbc) {
        this.storage = storage;
        this.jdbc = jdbc;
    }

    public record Stored(UUID id, String key, String name, String contentType, int sizeBytes, Integer width, Integer height) {}

    public Stored store(UUID org, UUID userId, byte[] bytes, String originalName, String purpose, boolean imagesOnly) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw ApiException.badRequest("Files must be between 1 byte and 5 MB");
        String type = sniff(bytes);
        if (type == null || (imagesOnly && type.equals("application/pdf"))) {
            throw ApiException.badRequest(imagesOnly ? "Only JPEG, PNG or WEBP images are accepted" : "Only JPEG, PNG, WEBP images or PDF files are accepted");
        }
        Integer w = null, h = null;
        if (!type.equals("application/pdf") && !type.equals("image/webp")) {
            var img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) throw ApiException.badRequest("The image could not be decoded");
            if ((long) img.getWidth() * img.getHeight() > 40_000_000L) throw ApiException.badRequest("Image dimensions too large");
            w = img.getWidth();
            h = img.getHeight();
        }
        UUID id = Ids.newId();
        String ext = switch (type) { case "image/jpeg" -> "jpg"; case "image/png" -> "png"; case "image/webp" -> "webp"; default -> "pdf"; };
        String folder = "PRODUCT".equals(purpose) ? "products" : "media";
        String key = "org/" + org + "/" + folder + "/" + id + "-" + Tokens.random(6) + "." + ext;
        storage.put(key, bytes, type);
        String name = originalName == null || originalName.isBlank() ? "upload." + ext : originalName.replaceAll("[^\\w.\\- ]", "_");
        if (name.length() > 255) name = name.substring(0, 255);
        jdbc.update("""
                INSERT INTO media_assets (id, organization_id, object_key, original_name, content_type, size_bytes, sha256, width, height, purpose, created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""", id, org, key, name, type, bytes.length, Tokens.sha256Hex(bytes), w, h, purpose, userId);
        return new Stored(id, key, name, type, bytes.length, w, h);
    }

    /** Presigned URL valid for one hour; null when storage is not configured (development). */
    public String url(String key) {
        return key == null || !storage.configured() ? null : storage.presignedGet(key, Duration.ofHours(1));
    }

    /** Removes a file of this store (bucket object and row). Unknown ids are ignored. */
    public void delete(UUID org, UUID mediaId) {
        if (mediaId == null) return;
        List<String> keys = jdbc.queryForList("DELETE FROM media_assets WHERE id = ? AND organization_id = ? RETURNING object_key", String.class, mediaId, org);
        if (!keys.isEmpty() && storage.configured()) storage.delete(keys.getFirst());
    }

    public static String sniff(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length > 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "application/pdf";
        return null;
    }
}
