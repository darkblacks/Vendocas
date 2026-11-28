package com.platform.storage;

import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.api.ApiException;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    private final StorageService storage;
    private final MediaService media;
    private final JdbcTemplate jdbc;

    public MediaController(StorageService storage, MediaService media, JdbcTemplate jdbc) {
        this.storage = storage;
        this.media = media;
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
        var s = media.store(t.orgId(), t.userId(), file.getBytes(), file.getOriginalFilename(), purpose, false);
        return new MediaView(s.id(), s.name(), s.contentType(), s.sizeBytes(), s.width(), s.height(), purpose, storage.presignedGet(s.key(), Duration.ofHours(1)), Instant.now());
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
}
