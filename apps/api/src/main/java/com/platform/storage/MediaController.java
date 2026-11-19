package com.platform.storage;

import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.shared.crypto.Tokens;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Media library. Uploads are validated by magic bytes (not by the declared content type or file
 * name), size-limited to 5 MB, decoded to confirm they are real images, and stored privately under
 * an organization-scoped random key.
 */
@RestController
@RequestMapping("/api/v1/media")
@Tag(name = "media")
public class MediaController {
    static final long MAX_BYTES = 5 * 1024 * 1024;
    private final StorageService storage;
    private final JdbcTemplate jdbc;

    public MediaController(StorageService storage, JdbcTemplate jdbc) {
        this.storage = storage;
        this.jdbc = jdbc;
    }

    public record MediaView(UUID id, String originalName, String contentType, long sizeBytes, Integer width, Integer height, String purpose,
                            String url, Instant createdAt) {}

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.CAMPAIGNS_WRITE)
    public MediaView upload(@RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "CAMPAIGN") String purpose) throws IOException {
        var t = TenantContext.current();
        if (!List.of("LOGO", "CAMPAIGN", "ATTACHMENT").contains(purpose)) throw ApiException.badRequest("Invalid purpose");
        byte[] bytes = file.getBytes();
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw ApiException.badRequest("Files must be between 1 byte and 5 MB");
        String type = sniff(bytes);
        if (type == null) throw ApiException.badRequest("Only JPEG, PNG, WEBP images or PDF files are accepted");
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
        String key = "org/" + t.orgId() + "/media/" + id + "-" + Tokens.random(6) + "." + ext;
        storage.put(key, bytes, type);
        String name = file.getOriginalFilename() == null ? "upload." + ext : file.getOriginalFilename().replaceAll("[^\\w.\\- ]", "_");
        jdbc.update("""
                INSERT INTO media_assets (id, organization_id, object_key, original_name, content_type, size_bytes, sha256, width, height, purpose, created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""", id, t.orgId(), key, name.length() > 255 ? name.substring(0, 255) : name, type, bytes.length,
                Tokens.sha256Hex(bytes), w, h, purpose, t.userId());
        return new MediaView(id, name, type, bytes.length, w, h, purpose, storage.presignedGet(key, Duration.ofHours(1)), Instant.now());
    }

    @GetMapping
    @RequiresPermission(Permission.CAMPAIGNS_READ)
    public List<MediaView> list() {
        return jdbc.query("""
                SELECT id, original_name, content_type, size_bytes, width, height, purpose, object_key, created_at FROM media_assets
                WHERE organization_id = ? ORDER BY created_at DESC LIMIT 200""",
                (rs, i) -> new MediaView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4), (Integer) rs.getObject(5),
                        (Integer) rs.getObject(6), rs.getString(7), storage.configured() ? storage.presignedGet(rs.getString(8), Duration.ofHours(1)) : null,
                        rs.getTimestamp(9).toInstant()), TenantContext.current().orgId());
    }

    static String sniff(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length > 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "application/pdf";
        return null;
    }
}
