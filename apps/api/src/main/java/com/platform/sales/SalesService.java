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
 * Vendocas PDV (sales registry). The Vendocas does not sell: the store registers what a customer bought elsewhere (counter,
 * WhatsApp, Instagram) so the CRM learns the customer's behaviour. Each sale becomes a COMPLETED order of the customer — the
 * B2C stage, the "thank you" alert and the reports treat it exactly like an online purchase.
 *
 * <ul>
 *   <li>Every plan: "venda seca" — customer + amount paid (enough for average ticket and repurchase window).</li>
 *   <li>PRODUCT_CATALOG (Alerta + Vendas and up): products and items, so the CRM knows <i>what</i> each customer buys.
 *       Catalogue prices come from the server, never from the client. Products with stock control lose the units sold
 *       (and get them back if the sale is cancelled); the cost at sale time is kept on the item for margins.</li>
 * </ul>
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
    private final ProductService products;

    public SalesService(JdbcTemplate jdbc, EntitlementService entitlements, CommerceService commerce, ContactService contacts,
                        ConsentService consents, AuditService audit, ProductService products) {
        this.products = products;
        this.jdbc = jdbc;
        this.entitlements = entitlements;
        this.commerce = commerce;
        this.contacts = contacts;
        this.consents = consents;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ sales

    public record NewCustomer(@Size(max = 250) String name, @Size(max = 30) String phone, @Email @Size(max = 254) String email) {}

    public record ItemInput(UUID productId, @Size(max = 200) String description, @Min(1) @Max(10000) int quantity, @PositiveOrZero Long unitAmountMinor) {}

    public record SaleInput(UUID contactId, @Valid NewCustomer customer, @Size(max = 100) List<@Valid ItemInput> items,
                            /* "venda seca": amount paid, used when there are no items */ @Positive Long amountMinor,
                            @Pattern(regexp = "[A-Z]{3}") String currency, @NotBlank String paymentMethod, @NotBlank String salesChannel,
                            Instant occurredAt, @Size(max = 500) String notes,
                            /* discount on the items' total (catalogue sales) */ @PositiveOrZero Long discountMinor,
                            /* "accepts alerts" checkbox: channels the customer agreed to receive alerts on */
                            List<@Pattern(regexp = "EMAIL|WHATSAPP|SMS") String> alertConsents) {}

    public record SaleResult(UUID orderId, UUID contactId, long totalMinor, String currency) {}

    @Transactional
    public SaleResult register(UUID org, UUID userId, SaleInput in) {
        entitlements.require(org, EntitlementService.SALES_REGISTRY);
        if (!PAYMENTS.contains(in.paymentMethod())) throw ApiException.badRequest("Invalid payment method");
        if (!CHANNELS.contains(in.salesChannel())) throw ApiException.badRequest("Invalid sales channel");
        if (in.occurredAt() != null && in.occurredAt().isAfter(Instant.now().plusSeconds(300))) throw ApiException.badRequest("Sale date is in the future");

        // ---- items (catalogue plans) or just the amount paid ("venda seca") ----
        record Line(UUID productId, String description, int quantity, long unit, String currency, Long cost, boolean track) {}
        List<Line> lines = new ArrayList<>();
        List<ItemInput> items = in.items() == null ? List.of() : in.items();
        if (items.isEmpty() && (in.amountMinor() == null || in.amountMinor() <= 0)) throw ApiException.badRequest("Inform the amount paid or the items");
        if (!items.isEmpty()) entitlements.require(org, EntitlementService.PRODUCT_CATALOG);
        for (ItemInput it : items) {
            if (it.productId() != null) {
                var p = jdbc.queryForList("SELECT name, price_minor, currency, cost_minor, track_stock FROM products WHERE id = ? AND organization_id = ? AND active",
                        it.productId(), org);
                if (p.isEmpty()) throw ApiException.notFound("Product");
                var row = p.getFirst();
                lines.add(new Line(it.productId(), (String) row.get("name"), it.quantity(), ((Number) row.get("price_minor")).longValue(), (String) row.get("currency"),
                        row.get("cost_minor") == null ? null : ((Number) row.get("cost_minor")).longValue(), (Boolean) row.get("track_stock")));
            } else {
                if (it.description() == null || it.description().isBlank() || it.unitAmountMinor() == null) {
                    throw ApiException.badRequest("Items without a product need a description and a unit amount");
                }
                lines.add(new Line(null, it.description().trim(), it.quantity(), it.unitAmountMinor(), null, null, false));
            }
        }
        String currency = in.currency() != null ? in.currency()
                : lines.stream().map(Line::currency).filter(Objects::nonNull).findFirst()
                .orElseGet(() -> jdbc.queryForObject("SELECT billing_currency FROM organizations WHERE id = ?", String.class, org));
        if (lines.stream().anyMatch(l -> l.currency() != null && !l.currency().equals(currency))) throw ApiException.badRequest("All items must use the sale currency");
        long total = 0;
        for (Line l : lines) total = Math.addExact(total, Math.multiplyExact(l.unit(), (long) l.quantity()));
        long discount = in.discountMinor() == null ? 0 : in.discountMinor();
        if (discount > 0 && lines.isEmpty()) throw ApiException.badRequest("A discount needs the sale items");
        if (discount > total) throw ApiException.badRequest("The discount is larger than the sale");
        total -= discount;
        if (lines.isEmpty()) total = in.amountMinor();

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
        jdbc.update("UPDATE orders SET sales_channel = ?, payment_method = ?, registered_by = ?, notes = ?, discount_minor = ? WHERE id = ?",
                in.salesChannel(), in.paymentMethod(), userId, in.notes(), discount, orderId);
        // Lock tracked products in a fixed order so two sales of the same products never deadlock.
        lines.stream().filter(Line::track).map(Line::productId).distinct().sorted().forEach(id -> products.lock(org, id));
        for (Line l : lines) {
            jdbc.update("INSERT INTO order_items (id, order_id, organization_id, product_id, description, quantity, unit_amount_minor, unit_cost_minor) VALUES (?,?,?,?,?,?,?,?)",
                    Ids.newId(), orderId, org, l.productId(), l.description(), l.quantity(), l.unit(), l.cost());
            if (l.track()) products.move(org, userId, l.productId(), "SALE", -l.quantity(), l.cost(), orderId, null);
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

    public record CancelInput(@Size(max = 300) String reason) {}

    /**
     * Cancels a registered sale: the order becomes CANCELED (it leaves the totals, the customer's stage is recalculated and
     * the attribution to a message is undone), the customer's lifetime value is reduced and tracked products get their
     * units back.
     */
    @Transactional
    public SaleDetail cancel(UUID org, UUID userId, UUID orderId, CancelInput in) {
        entitlements.require(org, EntitlementService.SALES_REGISTRY);
        var rows = jdbc.queryForList("SELECT external_id, contact_id, status, amount_minor, currency, occurred_at FROM orders WHERE id = ? AND organization_id = ? AND sales_channel IS NOT NULL FOR UPDATE",
                orderId, org);
        if (rows.isEmpty()) throw ApiException.notFound("Sale");
        var o = rows.getFirst();
        if (!"COMPLETED".equals(o.get("status"))) throw ApiException.conflict("This sale is already cancelled");
        UUID contactId = (UUID) o.get("contact_id");
        long amount = ((Number) o.get("amount_minor")).longValue();
        String currency = (String) o.get("currency");
        commerce.upsertOrder(org, new CommerceService.OrderInput((String) o.get("external_id"), contactId, "CANCELED", amount, currency,
                ((Timestamp) o.get("occurred_at")).toInstant()), "MANUAL");
        jdbc.update("UPDATE orders SET canceled_at = now(), canceled_by = ?, cancel_reason = ? WHERE id = ?", userId, in == null ? null : in.reason(), orderId);
        if (contactId != null) {
            jdbc.update("UPDATE contacts SET lifetime_value_minor = GREATEST(COALESCE(lifetime_value_minor, 0) - ?, 0) WHERE id = ? AND lifetime_value_currency = ?",
                    amount, contactId, currency);
        }
        var back = jdbc.queryForList("""
                SELECT i.product_id, sum(i.quantity) AS qty, max(i.unit_cost_minor) AS cost FROM order_items i JOIN products p ON p.id = i.product_id
                WHERE i.order_id = ? AND p.track_stock GROUP BY i.product_id ORDER BY i.product_id""", orderId);
        for (var b : back) {
            UUID pid = (UUID) b.get("product_id");
            products.lock(org, pid);
            products.move(org, userId, pid, "SALE_CANCELED", ((Number) b.get("qty")).intValue(), b.get("cost") == null ? null : ((Number) b.get("cost")).longValue(), orderId, null);
        }
        audit.user(org, userId, "sale.canceled", "order", orderId, Map.of("totalMinor", amount, "reason", in == null || in.reason() == null ? "" : in.reason()));
        return detail(org, orderId);
    }

    public record SaleItemView(UUID productId, String description, int quantity, long unitAmountMinor, Long unitCostMinor, String unit) {}

    public record SaleDetail(UUID id, Instant occurredAt, UUID contactId, String contactName, String status, long totalMinor, long discountMinor,
                             String currency, String salesChannel, String paymentMethod, String notes, String registeredBy, Instant canceledAt,
                             String canceledBy, String cancelReason, List<SaleItemView> items) {}

    public SaleDetail detail(UUID org, UUID orderId) {
        entitlements.require(org, EntitlementService.SALES_REGISTRY);
        var items = jdbc.query("""
                SELECT i.product_id, i.description, i.quantity, i.unit_amount_minor, i.unit_cost_minor, p.unit FROM order_items i
                LEFT JOIN products p ON p.id = i.product_id WHERE i.order_id = ? AND i.organization_id = ? ORDER BY i.description""",
                (rs, i) -> new SaleItemView(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getLong(4), (Long) rs.getObject(5), rs.getString(6)),
                orderId, org);
        var list = jdbc.query("""
                SELECT o.id, o.occurred_at, o.contact_id, c.name, o.status, o.amount_minor, o.discount_minor, o.currency, o.sales_channel, o.payment_method,
                       o.notes, ru.full_name, o.canceled_at, cu.full_name, o.cancel_reason
                FROM orders o LEFT JOIN contacts c ON c.id = o.contact_id LEFT JOIN users ru ON ru.id = o.registered_by LEFT JOIN users cu ON cu.id = o.canceled_by
                WHERE o.id = ? AND o.organization_id = ? AND o.sales_channel IS NOT NULL""",
                (rs, i) -> new SaleDetail(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                        rs.getLong(6), rs.getLong(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                        rs.getTimestamp(13) == null ? null : rs.getTimestamp(13).toInstant(), rs.getString(14), rs.getString(15), items), orderId, org);
        if (list.isEmpty()) throw ApiException.notFound("Sale");
        return list.getFirst();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String like(String q) { return q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%"; }

    public record SaleView(UUID id, Instant occurredAt, UUID contactId, String contactName, String status, long totalMinor, String currency,
                           String salesChannel, String paymentMethod, int items, String firstItem, String notes, long discountMinor) {}

    public List<SaleView> list(UUID org, Instant from, Instant to, String salesChannel) {
        return list(org, from, to, salesChannel, null, null);
    }

    /** Sales of the period, optionally filtered by channel, status (COMPLETED / CANCELED) and customer name/phone/e-mail. */
    public List<SaleView> list(UUID org, Instant from, Instant to, String salesChannel, String status, String q) {
        entitlements.require(org, EntitlementService.SALES_REGISTRY);
        return jdbc.query("""
                SELECT o.id, o.occurred_at, o.contact_id, c.name, o.status, o.amount_minor, o.currency, o.sales_channel, o.payment_method,
                       (SELECT COALESCE(sum(quantity), 0) FROM order_items i WHERE i.order_id = o.id),
                       (SELECT description FROM order_items i WHERE i.order_id = o.id ORDER BY i.unit_amount_minor DESC LIMIT 1), o.notes, o.discount_minor
                FROM orders o LEFT JOIN contacts c ON c.id = o.contact_id
                WHERE o.organization_id = ? AND o.sales_channel IS NOT NULL AND o.occurred_at >= ? AND o.occurred_at < ?
                  AND (?::text IS NULL OR o.sales_channel = ?) AND (?::text IS NULL OR o.status = ?)
                  AND (?::text IS NULL OR lower(COALESCE(c.name,'')) LIKE ? OR c.phone_e164 LIKE ? OR lower(COALESCE(c.email::text,'')) LIKE ?)
                ORDER BY o.occurred_at DESC LIMIT 1000""",
                (rs, i) -> new SaleView(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getString(5), rs.getLong(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10), rs.getString(11), rs.getString(12), rs.getLong(13)),
                org, Timestamp.from(from), Timestamp.from(to), salesChannel, salesChannel, status, status, like(q), like(q), like(q), like(q));
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
