package com.platform.sales;

import com.platform.audit.AuditService;
import com.platform.billing.EntitlementService;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.storage.MediaService;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Catalogue and stock of the Vendocas PDV (plan feature PRODUCT_CATALOG — Alerta + Vendas and up): categories, products
 * with price, cost, barcode, unit and photo, and stock control. Every change of a product's stock goes through
 * {@link #move} and leaves a row in {@code stock_movements} (the ledger), written under a row lock so concurrent sales
 * never lose units. The counter is never blocked: stock may go negative and shows up as such.
 */
@Service
public class ProductService {
    public static final Set<String> UNITS = Set.of("UN", "KG", "G", "L", "ML", "M", "CX", "PCT", "PAR", "SERV");
    private final JdbcTemplate jdbc;
    private final EntitlementService entitlements;
    private final MediaService media;
    private final AuditService audit;

    public ProductService(JdbcTemplate jdbc, EntitlementService entitlements, MediaService media, AuditService audit) {
        this.jdbc = jdbc;
        this.entitlements = entitlements;
        this.media = media;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ categories

    public record CategoryInput(@NotBlank @Size(max = 100) String name, Integer position) {}

    public record CategoryView(UUID id, String name, int position, long products) {}

    public List<CategoryView> categories(UUID org) {
        entitlements.require(org, EntitlementService.PRODUCT_CATALOG);
        return jdbc.query("""
                SELECT c.id, c.name, c.position, (SELECT count(*) FROM products p WHERE p.category_id = c.id AND p.active)
                FROM product_categories c WHERE c.organization_id = ? ORDER BY c.position, lower(c.name)""",
                (rs, i) -> new CategoryView(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getLong(4)), org);
    }

    @Transactional
    public UUID saveCategory(UUID org, UUID userId, UUID id, CategoryInput in) {
        entitlements.require(org, EntitlementService.PRODUCT_CATALOG);
        String name = in.name().trim();
        Integer dup = jdbc.queryForObject("SELECT count(*) FROM product_categories WHERE organization_id = ? AND lower(name) = lower(?) AND (?::uuid IS NULL OR id <> ?)",
                Integer.class, org, name, id, id);
        if (dup != null && dup > 0) throw ApiException.conflict("A category with this name already exists");
        if (id == null) {
            id = Ids.newId();
            jdbc.update("INSERT INTO product_categories (id, organization_id, name, position) VALUES (?,?,?,?)", id, org, name, in.position() == null ? 0 : in.position());
        } else {
            int n = jdbc.update("UPDATE product_categories SET name = ?, position = COALESCE(?, position) WHERE id = ? AND organization_id = ?", name, in.position(), id, org);
            if (n == 0) throw ApiException.notFound("Category");
            jdbc.update("UPDATE products SET category = ? WHERE category_id = ?", name, id);
        }
        audit.user(org, userId, "product_category.saved", "product_category", id, Map.of("name", name));
        return id;
    }

    /** Deletes a category; its products stay, without category. */
    @Transactional
    public void deleteCategory(UUID org, UUID userId, UUID id) {
        entitlements.require(org, EntitlementService.PRODUCT_CATALOG);
        jdbc.update("UPDATE products SET category = NULL WHERE category_id = ? AND organization_id = ?", id, org);
        if (jdbc.update("DELETE FROM product_categories WHERE id = ? AND organization_id = ?", id, org) == 0) throw ApiException.notFound("Category");
        audit.user(org, userId, "product_category.deleted", "product_category", id, Map.of());
    }

    private UUID categoryFor(UUID org, UUID categoryId, String legacyName) {
        if (categoryId != null) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM product_categories WHERE id = ? AND organization_id = ?", Integer.class, categoryId, org);
            if (n == null || n == 0) throw ApiException.notFound("Category");
            return categoryId;
        }
        if (legacyName == null || legacyName.isBlank()) return null;
        // Older clients send the category as text: find it or create it.
        var found = jdbc.queryForList("SELECT id FROM product_categories WHERE organization_id = ? AND lower(name) = lower(?)", UUID.class, org, legacyName.trim());
        if (!found.isEmpty()) return found.getFirst();
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO product_categories (id, organization_id, name) VALUES (?,?,?)", id, org, legacyName.trim());
        return id;
    }

    // ------------------------------------------------------------------ products

    /**
     * PUT replaces every field (null clears it), except {@code unit} and {@code trackStock} (null keeps them). Stock is
     * never set here: {@code initialStock} is only read when creating a product, afterwards use the stock endpoints.
     */
    public record ProductInput(@NotBlank @Size(max = 200) String name, @Size(max = 80) String sku, @PositiveOrZero long priceMinor,
                               @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency, @Size(max = 100) String category, UUID categoryId,
                               @Size(max = 1000) String description, @PositiveOrZero Long costMinor, @Size(max = 50) @Pattern(regexp = "[0-9A-Za-z\\-]*") String barcode,
                               String unit, Boolean trackStock, @PositiveOrZero Integer minStock, @Min(-1_000_000) @Max(1_000_000) Integer initialStock,
                               Boolean active) {}

    public record ProductView(UUID id, String name, String sku, long priceMinor, String currency, String category, UUID categoryId,
                              String description, Long costMinor, String barcode, String unit, boolean trackStock, int stockQuantity,
                              Integer minStock, boolean lowStock, String photoUrl, boolean active, Instant updatedAt) {}

    private static final String PRODUCT_COLS = """
            p.id, p.name, p.sku, p.price_minor, p.currency, c.name AS category, p.category_id, p.description, p.cost_minor, p.barcode, p.unit,
            p.track_stock, p.stock_quantity, p.min_stock, m.object_key AS photo_key, p.active, p.updated_at
            FROM products p LEFT JOIN product_categories c ON c.id = p.category_id LEFT JOIN media_assets m ON m.id = p.photo_id""";

    private ProductView row(ResultSet rs) throws SQLException {
        boolean track = rs.getBoolean("track_stock");
        int qty = rs.getInt("stock_quantity");
        Integer min = (Integer) rs.getObject("min_stock");
        boolean low = track && (qty <= 0 || (min != null && qty <= min));
        return new ProductView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("sku"), rs.getLong("price_minor"), rs.getString("currency"),
                rs.getString("category"), rs.getObject("category_id", UUID.class), rs.getString("description"), (Long) rs.getObject("cost_minor"),
                rs.getString("barcode"), rs.getString("unit"), track, qty, min, low, media.url(rs.getString("photo_key")), rs.getBoolean("active"),
                rs.getTimestamp("updated_at").toInstant());
    }

    /** Search by name, SKU or barcode (a scanner types the barcode); optional category and low-stock filters. */
    public List<ProductView> products(UUID org, String q, boolean includeInactive, UUID categoryId, boolean lowStockOnly) {
        entitlements.require(org, EntitlementService.PRODUCT_CATALOG);
        String like = q == null ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.query("SELECT " + PRODUCT_COLS + """
                 WHERE p.organization_id = ? AND (? OR p.active)
                  AND (?::text IS NULL OR lower(p.name) LIKE ? OR lower(COALESCE(p.sku,'')) LIKE ? OR p.barcode = ?)
                  AND (?::uuid IS NULL OR p.category_id = ?)
                  AND (NOT ? OR (p.track_stock AND (p.stock_quantity <= 0 OR p.stock_quantity <= COALESCE(p.min_stock, 0))))
                ORDER BY lower(p.name) LIMIT 1000""", (rs, i) -> row(rs),
                org, includeInactive, q, like, like, q == null ? null : q.trim(), categoryId, categoryId, lowStockOnly);
    }

    public ProductView product(UUID org, UUID id) {
        entitlements.require(org, EntitlementService.PRODUCT_CATALOG);
        var list = jdbc.query("SELECT " + PRODUCT_COLS + " WHERE p.id = ? AND p.organization_id = ?", (rs, i) -> row(rs), id, org);
        if (list.isEmpty()) throw ApiException.notFound("Product");
        return list.getFirst();
    }

    @Transactional
    public UUID saveProduct(UUID org, UUID userId, UUID id, ProductInput in) {
        entitlements.require(org, EntitlementService.PRODUCT_CATALOG);
        String sku = blank(in.sku()), barcode = blank(in.barcode());
        if (in.unit() != null && !UNITS.contains(in.unit())) throw ApiException.badRequest("Invalid unit");
        if (sku != null && exists("SELECT count(*) FROM products WHERE organization_id = ? AND sku = ? AND (?::uuid IS NULL OR id <> ?)", org, sku, id, id)) {
            throw ApiException.conflict("Another product already uses this SKU");
        }
        if (barcode != null && exists("SELECT count(*) FROM products WHERE organization_id = ? AND barcode = ? AND (?::uuid IS NULL OR id <> ?)", org, barcode, id, id)) {
            throw ApiException.conflict("Another product already uses this barcode");
        }
        UUID categoryId = categoryFor(org, in.categoryId(), in.category());
        String categoryName = categoryId == null ? null : jdbc.queryForObject("SELECT name FROM product_categories WHERE id = ?", String.class, categoryId);
        if (id == null) {
            id = Ids.newId();
            boolean track = Boolean.TRUE.equals(in.trackStock());
            jdbc.update("""
                    INSERT INTO products (id, organization_id, name, sku, price_minor, currency, category, category_id, description, cost_minor, barcode,
                      unit, track_stock, min_stock, active) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    id, org, in.name().trim(), sku, in.priceMinor(), in.currency(), categoryName, categoryId, blank(in.description()), in.costMinor(), barcode,
                    in.unit() == null ? "UN" : in.unit(), track, in.minStock(), in.active() == null || in.active());
            if (track && in.initialStock() != null && in.initialStock() != 0) move(org, userId, id, "INITIAL", in.initialStock(), in.costMinor(), null, null);
        } else {
            int n = jdbc.update("""
                    UPDATE products SET name = ?, sku = ?, price_minor = ?, currency = ?, category = ?, category_id = ?, description = ?, cost_minor = ?,
                      barcode = ?, unit = COALESCE(?, unit), track_stock = COALESCE(?, track_stock), min_stock = ?, active = COALESCE(?, active), updated_at = now()
                    WHERE id = ? AND organization_id = ?""", in.name().trim(), sku, in.priceMinor(), in.currency(), categoryName, categoryId,
                    blank(in.description()), in.costMinor(), barcode, in.unit(), in.trackStock(), in.minStock(), in.active(), id, org);
            if (n == 0) throw ApiException.notFound("Product");
        }
        audit.user(org, userId, "product.saved", "product", id, Map.of("name", in.name()));
        return id;
    }

    /** Replaces the product photo (stored under the store's own prefix in the bucket); the previous file is deleted. */
    @Transactional
    public ProductView setPhoto(UUID org, UUID userId, UUID productId, byte[] bytes, String filename) throws IOException {
        entitlements.require(org, EntitlementService.PRODUCT_CATALOG);
        UUID previous = currentPhoto(org, productId);
        var stored = media.store(org, userId, bytes, filename, "PRODUCT", true);
        jdbc.update("UPDATE products SET photo_id = ?, updated_at = now() WHERE id = ? AND organization_id = ?", stored.id(), productId, org);
        media.delete(org, previous);
        audit.user(org, userId, "product.photo_set", "product", productId, Map.of("media", stored.id().toString()));
        return product(org, productId);
    }

    @Transactional
    public void removePhoto(UUID org, UUID userId, UUID productId) {
        entitlements.require(org, EntitlementService.PRODUCT_CATALOG);
        UUID previous = currentPhoto(org, productId);
        jdbc.update("UPDATE products SET photo_id = NULL, updated_at = now() WHERE id = ? AND organization_id = ?", productId, org);
        media.delete(org, previous);
        audit.user(org, userId, "product.photo_removed", "product", productId, Map.of());
    }

    private UUID currentPhoto(UUID org, UUID productId) {
        var rows = jdbc.queryForList("SELECT photo_id FROM products WHERE id = ? AND organization_id = ?", productId, org);
        if (rows.isEmpty()) throw ApiException.notFound("Product");
        return (UUID) rows.getFirst().get("photo_id");
    }

    // ------------------------------------------------------------------ stock

    /**
     * PURCHASE: units received (+quantity; optional unit cost updates the product cost). LOSS: broken, expired or stolen
     * (−quantity). COUNT: inventory — quantity is the number counted on the shelf, the difference is recorded.
     */
    public record StockInput(@NotBlank @Pattern(regexp = "PURCHASE|LOSS|COUNT") String kind, @Min(0) @Max(1_000_000) int quantity,
                             @PositiveOrZero Long unitCostMinor, @Size(max = 300) String reason) {}

    public record MovementView(UUID id, UUID productId, String productName, String kind, int quantity, int balanceAfter, Long unitCostMinor,
                               UUID orderId, String reason, String createdBy, Instant createdAt) {}

    @Transactional
    public ProductView adjustStock(UUID org, UUID userId, UUID productId, StockInput in) {
        entitlements.require(org, EntitlementService.PRODUCT_CATALOG);
        var p = lock(org, productId);
        if (!p.track()) throw ApiException.badRequest("Stock control is off for this product");
        int delta = switch (in.kind()) {
            case "PURCHASE" -> in.quantity();
            case "LOSS" -> -in.quantity();
            default -> in.quantity() - p.stock();
        };
        if (delta == 0 && !"COUNT".equals(in.kind())) throw ApiException.badRequest("Quantity must be greater than zero");
        if (delta != 0) move(org, userId, productId, in.kind(), delta, in.unitCostMinor(), null, blank(in.reason()));
        if ("PURCHASE".equals(in.kind()) && in.unitCostMinor() != null) {
            jdbc.update("UPDATE products SET cost_minor = ?, updated_at = now() WHERE id = ?", in.unitCostMinor(), productId);
        }
        audit.user(org, userId, "stock." + in.kind().toLowerCase(Locale.ROOT), "product", productId, Map.of("delta", delta));
        return product(org, productId);
    }

    record Locked(boolean track, int stock, Long cost) {}

    Locked lock(UUID org, UUID productId) {
        var rows = jdbc.queryForList("SELECT track_stock, stock_quantity, cost_minor FROM products WHERE id = ? AND organization_id = ? FOR UPDATE", productId, org);
        if (rows.isEmpty()) throw ApiException.notFound("Product");
        var r = rows.getFirst();
        return new Locked((Boolean) r.get("track_stock"), ((Number) r.get("stock_quantity")).intValue(), r.get("cost_minor") == null ? null : ((Number) r.get("cost_minor")).longValue());
    }

    /** Applies a signed delta to a product's stock and writes the ledger row. Caller must be in a transaction. */
    void move(UUID org, UUID userId, UUID productId, String kind, int delta, Long unitCost, UUID orderId, String reason) {
        Integer balance = jdbc.queryForObject("UPDATE products SET stock_quantity = stock_quantity + ?, updated_at = now() WHERE id = ? AND organization_id = ? RETURNING stock_quantity",
                Integer.class, delta, productId, org);
        jdbc.update("""
                INSERT INTO stock_movements (id, organization_id, product_id, kind, quantity, balance_after, unit_cost_minor, order_id, reason, created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)""", Ids.newId(), org, productId, kind, delta, balance, unitCost, orderId, reason, userId);
    }

    public List<MovementView> movements(UUID org, UUID productId, int limit) {
        entitlements.require(org, EntitlementService.PRODUCT_CATALOG);
        return jdbc.query("""
                SELECT s.id, s.product_id, p.name, s.kind, s.quantity, s.balance_after, s.unit_cost_minor, s.order_id, s.reason, u.full_name, s.created_at
                FROM stock_movements s JOIN products p ON p.id = s.product_id LEFT JOIN users u ON u.id = s.created_by
                WHERE s.organization_id = ? AND (?::uuid IS NULL OR s.product_id = ?) ORDER BY s.created_at DESC LIMIT ?""",
                (rs, i) -> new MovementView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6),
                        (Long) rs.getObject(7), rs.getObject(8, UUID.class), rs.getString(9), rs.getString(10), rs.getTimestamp(11).toInstant()),
                org, productId, productId, Math.min(Math.max(limit, 1), 500));
    }

    public record StockSummary(String currency, long trackedProducts, long units, long valueAtCostMinor, long valueAtPriceMinor,
                               long lowStock, long outOfStock, long withoutCost) {}

    /** Stock value only counts positive balances; products without a cost are counted apart. */
    public StockSummary stockSummary(UUID org) {
        entitlements.require(org, EntitlementService.PRODUCT_CATALOG);
        return jdbc.queryForObject("""
                SELECT max(currency), count(*), COALESCE(sum(GREATEST(stock_quantity, 0)), 0),
                       COALESCE(sum(GREATEST(stock_quantity, 0) * COALESCE(cost_minor, 0)), 0), COALESCE(sum(GREATEST(stock_quantity, 0) * price_minor), 0),
                       count(*) FILTER (WHERE stock_quantity > 0 AND min_stock IS NOT NULL AND stock_quantity <= min_stock),
                       count(*) FILTER (WHERE stock_quantity <= 0), count(*) FILTER (WHERE cost_minor IS NULL)
                FROM products WHERE organization_id = ? AND active AND track_stock""",
                (rs, i) -> new StockSummary(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)), org);
    }

    // ------------------------------------------------------------------ reports

    public record ProductSales(UUID productId, String name, String category, long quantity, long revenueMinor, Long costMinor, Long marginMinor) {}

    /** Sales of the period per product (cancelled sales excluded). Margin only when every unit sold had a cost. */
    public List<ProductSales> salesByProduct(UUID org, Instant from, Instant to) {
        entitlements.require(org, EntitlementService.PRODUCT_CATALOG);
        return jdbc.query("""
                SELECT i.product_id, COALESCE(p.name, i.description), c.name, sum(i.quantity), sum(i.quantity * i.unit_amount_minor),
                       CASE WHEN bool_and(i.unit_cost_minor IS NOT NULL) THEN sum(i.quantity * i.unit_cost_minor) END
                FROM order_items i JOIN orders o ON o.id = i.order_id LEFT JOIN products p ON p.id = i.product_id
                LEFT JOIN product_categories c ON c.id = p.category_id
                WHERE i.organization_id = ? AND o.status = 'COMPLETED' AND o.sales_channel IS NOT NULL AND o.occurred_at >= ? AND o.occurred_at < ?
                GROUP BY i.product_id, COALESCE(p.name, i.description), c.name ORDER BY 5 DESC LIMIT 200""",
                (rs, i) -> {
                    Long cost = rs.getObject(6) == null ? null : rs.getLong(6);
                    long revenue = rs.getLong(5);
                    return new ProductSales(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4), revenue, cost, cost == null ? null : revenue - cost);
                }, org, Timestamp.from(from), Timestamp.from(to));
    }

    private boolean exists(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n != null && n > 0;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
