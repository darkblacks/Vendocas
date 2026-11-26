package com.platform.sales;

import com.platform.audit.AuditService;
import com.platform.billing.EntitlementService;
import com.platform.commerce.CommerceService;
import com.platform.consent.ConsentService;
import com.platform.contacts.ContactDtos.ContactInput;
import com.platform.contacts.ContactService;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Sales Registry (plan Alerta + Vendas and above): sales made at the counter, on WhatsApp or Instagram are registered in
 * the Vendocas and become COMPLETED orders of the customer — so the CRM stage, the "thank you" alert and the reports
 * treat them exactly like an online purchase. Prices of catalogue products come from the server, never from the client.
 */
@Service
public class SalesService {
    static final Set<String> CHANNELS = Set.of("STORE", "WHATSAPP", "INSTAGRAM", "ONLINE", "PHONE", "OTHER");
    static final Set<String> PAYMENTS = Set.of("PIX", "CARD", "CASH", "BOLETO", "TRANSFER", "OTHER");
    /** Purposes covered by "the customer accepts alerts on WhatsApp/SMS/e-mail" ticked when registering a sale. */
    static final List<String> ALERT_PURPOSES = List.of("TRANSACTIONAL", "SERVICE_REMINDER", "MARKETING", "REACTIVATION");
    private final JdbcTemplate jdbc;
    private final EntitlementService entitlements;
    private final CommerceService commerce;
    private final ContactService contacts;
    private final ConsentService consents;
    private final AuditService audit;

    public SalesService(JdbcTemplate jdbc, EntitlementService entitlements, CommerceService commerce, ContactService contacts,
                        ConsentService consents, AuditService audit) {
        this.jdbc = jdbc;
        this.entitlements = entitlements;
        this.commerce = commerce;
        this.contacts = contacts;
        this.consents = consents;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ products

    public record ProductInput(@NotBlank @Size(max = 200) String name, @Size(max = 80) String sku, @PositiveOrZero long priceMinor,
                               @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency, @Size(max = 100) String category, Boolean active) {}

    public record ProductView(UUID id, String name, String sku, long priceMinor, String currency, String category, boolean active) {}

    public List<ProductView> products(UUID org, String q, boolean includeInactive) {
        entitlements.require(org, EntitlementService.SALES_REGISTRY);
        return jdbc.query("""
                SELECT id, name, sku, price_minor, currency, category, active FROM products WHERE organization_id = ? AND (? OR active)
                  AND (?::text IS NULL OR lower(name) LIKE ? OR lower(COALESCE(sku,'')) LIKE ?) ORDER BY name LIMIT 500""",
                (rs, i) -> new ProductView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6),
                        rs.getBoolean(7)), org, includeInactive, q, like(q), like(q));
    }

    private static String like(String q) { return q == null ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%"; }

    @Transactional
    public UUID saveProduct(UUID org, UUID userId, UUID id, ProductInput in) {
        entitlements.require(org, EntitlementService.SALES_REGISTRY);
        String sku = in.sku() == null || in.sku().isBlank() ? null : in.sku().trim();
        if (sku != null) {
            Integer dup = jdbc.queryForObject("SELECT count(*) FROM products WHERE organization_id = ? AND sku = ? AND (?::uuid IS NULL OR id <> ?)",
                    Integer.class, org, sku, id, id);
            if (dup != null && dup > 0) throw ApiException.conflict("Another product already uses this SKU");
        }
        if (id == null) {
            id = Ids.newId();
            jdbc.update("INSERT INTO products (id, organization_id, name, sku, price_minor, currency, category, active) VALUES (?,?,?,?,?,?,?,?)",
                    id, org, in.name().trim(), sku, in.priceMinor(), in.currency(), in.category(), in.active() == null || in.active());
        } else {
            int n = jdbc.update("""
                    UPDATE products SET name = ?, sku = ?, price_minor = ?, currency = ?, category = ?, active = COALESCE(?, active), updated_at = now()
                    WHERE id = ? AND organization_id = ?""", in.name().trim(), sku, in.priceMinor(), in.currency(), in.category(), in.active(), id, org);
            if (n == 0) throw ApiException.notFound("Product");
        }
        audit.user(org, userId, "product.saved", "product", id, Map.of("name", in.name()));
        return id;
    }

    // ------------------------------------------------------------------ sales

    public record NewCustomer(@Size(max = 250) String name, @Size(max = 30) String phone, @Email @Size(max = 254) String email) {}

    public record ItemInput(UUID productId, @Size(max = 200) String description, @Min(1) @Max(10000) int quantity, @PositiveOrZero Long unitAmountMinor) {}

    public record SaleInput(UUID contactId, @Valid NewCustomer customer, @NotEmpty @Size(max = 100) List<@Valid ItemInput> items,
                            @Pattern(regexp = "[A-Z]{3}") String currency, @NotBlank String paymentMethod, @NotBlank String salesChannel,
                            Instant occurredAt, @Size(max = 500) String notes,
                            /* "accepts alerts" checkbox: channels the customer agreed to receive alerts on */
                            List<@Pattern(regexp = "EMAIL|WHATSAPP|SMS") String> alertConsents) {}

    public record SaleResult(UUID orderId, UUID contactId, long totalMinor, String currency) {}

    @Transactional
    public SaleResult register(UUID org, UUID userId, SaleInput in) {
        entitlements.require(org, EntitlementService.SALES_REGISTRY);
        if (!PAYMENTS.contains(in.paymentMethod())) throw ApiException.badRequest("Invalid payment method");
        if (!CHANNELS.contains(in.salesChannel())) throw ApiException.badRequest("Invalid sales channel");
        if (in.occurredAt() != null && in.occurredAt().isAfter(Instant.now().plusSeconds(300))) throw ApiException.badRequest("Sale date is in the future");

        // ---- items: catalogue prices are authoritative ----
        record Line(UUID productId, String description, int quantity, long unit, String currency) {}
        List<Line> lines = new ArrayList<>();
        for (ItemInput it : in.items()) {
            if (it.productId() != null) {
                var p = jdbc.queryForList("SELECT name, price_minor, currency FROM products WHERE id = ? AND organization_id = ? AND active", it.productId(), org);
                if (p.isEmpty()) throw ApiException.notFound("Product");
                var row = p.getFirst();
                lines.add(new Line(it.productId(), (String) row.get("name"), it.quantity(), ((Number) row.get("price_minor")).longValue(), (String) row.get("currency")));
            } else {
                if (it.description() == null || it.description().isBlank() || it.unitAmountMinor() == null) {
                    throw ApiException.badRequest("Items without a product need a description and a unit amount");
                }
                lines.add(new Line(null, it.description().trim(), it.quantity(), it.unitAmountMinor(), null));
            }
        }
        String currency = in.currency() != null ? in.currency()
                : lines.stream().map(Line::currency).filter(Objects::nonNull).findFirst()
                .orElseGet(() -> jdbc.queryForObject("SELECT billing_currency FROM organizations WHERE id = ?", String.class, org));
        if (lines.stream().anyMatch(l -> l.currency() != null && !l.currency().equals(currency))) throw ApiException.badRequest("All items must use the sale currency");
        long total = 0;
        for (Line l : lines) total = Math.addExact(total, Math.multiplyExact(l.unit(), (long) l.quantity()));

        // ---- customer ----
        UUID contactId = in.contactId();
        if (contactId != null) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM contacts WHERE id = ? AND organization_id = ? AND status = 'ACTIVE'", Integer.class, contactId, org);
            if (n == null || n == 0) throw ApiException.notFound("Contact");
        } else if (in.customer() != null && (notBlank(in.customer().phone()) || notBlank(in.customer().email()))) {
            contactId = contacts.upsert(org, new ContactInput(null, in.customer().name(), in.customer().email(), in.customer().phone(), null, null, null, null,
                    null, null, null, null, null, null, List.of(), null), "MANUAL", "sales-registry").getKey().id();
        }

        Instant at = in.occurredAt() == null ? Instant.now() : in.occurredAt();
        UUID orderId = commerce.upsertOrder(org, new CommerceService.OrderInput("sale:" + Ids.newId(), contactId, "COMPLETED", total, currency, at), "MANUAL");
        jdbc.update("UPDATE orders SET sales_channel = ?, payment_method = ?, registered_by = ?, notes = ? WHERE id = ?",
                in.salesChannel(), in.paymentMethod(), userId, in.notes(), orderId);
        for (Line l : lines) {
            jdbc.update("INSERT INTO order_items (id, order_id, organization_id, product_id, description, quantity, unit_amount_minor) VALUES (?,?,?,?,?,?,?)",
                    Ids.newId(), orderId, org, l.productId(), l.description(), l.quantity(), l.unit());
        }
        if (contactId != null && in.alertConsents() != null) {
            var ev = new ConsentService.Evidence("SALES_REGISTRY", "user:" + userId, null, null,
                    "Customer agreed at the point of sale to receive alerts from the store", null, at);
            for (String ch : new LinkedHashSet<>(in.alertConsents())) {
                for (String purpose : ALERT_PURPOSES) consents.set(org, contactId, ch, purpose, "GRANTED", "CONSENT", ev);
            }
        }
        audit.user(org, userId, "sale.registered", "order", orderId, Map.of("totalMinor", total, "currency", currency));
        return new SaleResult(orderId, contactId, total, currency);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    public record SaleView(UUID id, Instant occurredAt, UUID contactId, String contactName, String status, long totalMinor, String currency,
                           String salesChannel, String paymentMethod, int items, String firstItem, String notes) {}

    public List<SaleView> list(UUID org, Instant from, Instant to, String salesChannel) {
        entitlements.require(org, EntitlementService.SALES_REGISTRY);
        return jdbc.query("""
                SELECT o.id, o.occurred_at, o.contact_id, c.name, o.status, o.amount_minor, o.currency, o.sales_channel, o.payment_method,
                       (SELECT COALESCE(sum(quantity), 0) FROM order_items i WHERE i.order_id = o.id),
                       (SELECT description FROM order_items i WHERE i.order_id = o.id ORDER BY i.unit_amount_minor DESC LIMIT 1), o.notes
                FROM orders o LEFT JOIN contacts c ON c.id = o.contact_id
                WHERE o.organization_id = ? AND o.sales_channel IS NOT NULL AND o.occurred_at >= ? AND o.occurred_at < ?
                  AND (?::text IS NULL OR o.sales_channel = ?) ORDER BY o.occurred_at DESC LIMIT 1000""",
                (rs, i) -> new SaleView(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getString(5), rs.getLong(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10), rs.getString(11), rs.getString(12)),
                org, Timestamp.from(from), Timestamp.from(to), salesChannel, salesChannel);
    }

    public record SalesSummary(Instant from, Instant to, String currency, long totalMinor, long sales, Long averageTicketMinor, long newCustomers,
                               Map<String, Long> byChannel, Map<String, Long> byPayment) {}

    /** Totals of registered sales (COMPLETED) in the period; new customers = first purchase inside the period. */
    public SalesSummary summary(UUID org, Instant from, Instant to) {
        entitlements.require(org, EntitlementService.SALES_REGISTRY);
        var t = jdbc.queryForMap("""
                SELECT COALESCE(sum(amount_minor), 0) AS total, count(*) AS n, max(currency) AS currency FROM orders
                WHERE organization_id = ? AND sales_channel IS NOT NULL AND status = 'COMPLETED' AND occurred_at >= ? AND occurred_at < ?""",
                org, Timestamp.from(from), Timestamp.from(to));
        Map<String, Long> byChannel = new TreeMap<>(), byPayment = new TreeMap<>();
        jdbc.query("""
                SELECT sales_channel, payment_method, sum(amount_minor) FROM orders WHERE organization_id = ? AND sales_channel IS NOT NULL
                  AND status = 'COMPLETED' AND occurred_at >= ? AND occurred_at < ? GROUP BY 1, 2""", rs -> {
            byChannel.merge(rs.getString(1), rs.getLong(3), Long::sum);
            byPayment.merge(rs.getString(2), rs.getLong(3), Long::sum);
        }, org, Timestamp.from(from), Timestamp.from(to));
        Long newCustomers = jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT contact_id FROM orders WHERE organization_id = ? AND status = 'COMPLETED' AND contact_id IS NOT NULL
                  GROUP BY contact_id HAVING min(occurred_at) >= ? AND min(occurred_at) < ?) x""", Long.class, org, Timestamp.from(from), Timestamp.from(to));
        long total = ((Number) t.get("total")).longValue(), n = ((Number) t.get("n")).longValue();
        return new SalesSummary(from, to, (String) t.get("currency"), total, n, n == 0 ? null : total / n, newCustomers == null ? 0 : newCustomers, byChannel, byPayment);
    }
}
