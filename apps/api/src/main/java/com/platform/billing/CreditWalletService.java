package com.platform.billing;

import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * Prepaid communication wallet. Every mutation locks the wallet row (SELECT … FOR UPDATE), is
 * keyed by a unique idempotency key and writes a ledger row, so retries never double-charge.
 * Flow per paid message: reserve (before submit) → debit actual cost (after provider accepts)
 * or release (if blocked/failed/canceled).
 */
@Service
public class CreditWalletService {
    private final JdbcTemplate jdbc;

    public CreditWalletService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Wallet(String currency, long balanceMinor, long reservedMinor) {
        public long availableMinor() { return balanceMinor - reservedMinor; }
    }

    public Wallet get(UUID orgId) {
        return jdbc.queryForObject("SELECT currency, balance_minor, reserved_minor FROM credit_wallets WHERE organization_id = ?",
                (rs, i) -> new Wallet(rs.getString(1), rs.getLong(2), rs.getLong(3)), orgId);
    }

    public enum ReserveOutcome { RESERVED, ALREADY_RESERVED, INSUFFICIENT_BALANCE, DAILY_LIMIT, MONTHLY_LIMIT }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReserveOutcome reserve(UUID orgId, long amount, String key, String refType, UUID refId) {
        if (amount <= 0) return ReserveOutcome.RESERVED;
        Wallet w = lock(orgId);
        if (exists(key)) return ReserveOutcome.ALREADY_RESERVED;
        if (w.availableMinor() < amount) return ReserveOutcome.INSUFFICIENT_BALANCE;
        Map<String, Object> limits = jdbc.queryForMap("""
                SELECT s.daily_spend_limit_minor, s.monthly_spend_limit_minor, o.time_zone FROM organization_settings s
                JOIN organizations o ON o.id = s.organization_id WHERE s.organization_id = ?""", orgId);
        ZoneId zone = ZoneId.of((String) limits.get("time_zone"));
        LocalDate today = LocalDate.now(zone);
        long spentToday = spentSince(orgId, today.atStartOfDay(zone).toInstant()) + w.reservedMinor();
        if (spentToday + amount > ((Number) limits.get("daily_spend_limit_minor")).longValue()) return ReserveOutcome.DAILY_LIMIT;
        long spentMonth = spentSince(orgId, today.withDayOfMonth(1).atStartOfDay(zone).toInstant()) + w.reservedMinor();
        if (spentMonth + amount > ((Number) limits.get("monthly_spend_limit_minor")).longValue()) return ReserveOutcome.MONTHLY_LIMIT;
        jdbc.update("UPDATE credit_wallets SET reserved_minor = reserved_minor + ?, updated_at = now(), version = version + 1 WHERE organization_id = ?", amount, orgId);
        ledger(orgId, "RESERVE", 0, w.balanceMinor(), w.reservedMinor() + amount, refType, refId, key, "Reserved " + amount);
        return ReserveOutcome.RESERVED;
    }

    /** Converts a reservation into a charge of {@code actual} (≤ reserved; any difference is released). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void debit(UUID orgId, long reserved, long actual, String key, String refType, UUID refId) {
        Wallet w = lock(orgId);
        if (exists(key + ":debit")) return;
        long charge = Math.min(actual, w.balanceMinor());
        long newReserved = Math.max(0, w.reservedMinor() - reserved);
        jdbc.update("UPDATE credit_wallets SET balance_minor = balance_minor - ?, reserved_minor = ?, updated_at = now(), version = version + 1 WHERE organization_id = ?",
                charge, newReserved, orgId);
        ledger(orgId, "DEBIT", -charge, w.balanceMinor() - charge, newReserved, refType, refId, key + ":debit", "Usage charge");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID orgId, long reserved, String key, String refType, UUID refId) {
        if (reserved <= 0) return;
        Wallet w = lock(orgId);
        if (exists(key + ":release") || exists(key + ":debit") || !exists(key)) return;
        long newReserved = Math.max(0, w.reservedMinor() - reserved);
        jdbc.update("UPDATE credit_wallets SET reserved_minor = ?, updated_at = now(), version = version + 1 WHERE organization_id = ?", newReserved, orgId);
        ledger(orgId, "RELEASE", 0, w.balanceMinor(), newReserved, refType, refId, key + ":release", "Reservation released");
    }

    /** Credits a confirmed purchase (or a platform grant). Idempotent by key. */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean credit(UUID orgId, long amount, String currency, String type, String key, String refType, UUID refId, String description) {
        Wallet w = lock(orgId);
        if (exists(key)) return false;
        if (!w.currency().equals(currency)) throw new ApiException(ErrorCode.CONFLICT, "Wallet currency is " + w.currency());
        jdbc.update("UPDATE credit_wallets SET balance_minor = balance_minor + ?, low_balance_notified_at = NULL, updated_at = now(), version = version + 1 WHERE organization_id = ?",
                amount, orgId);
        ledger(orgId, type, amount, w.balanceMinor() + amount, w.reservedMinor(), refType, refId, key, description);
        return true;
    }

    private long spentSince(UUID orgId, java.time.Instant since) {
        Long v = jdbc.queryForObject("SELECT COALESCE(-SUM(amount_minor),0) FROM credit_transactions WHERE organization_id = ? AND type = 'DEBIT' AND created_at >= ?",
                Long.class, orgId, java.sql.Timestamp.from(since));
        return v == null ? 0 : v;
    }

    private Wallet lock(UUID orgId) {
        var rows = jdbc.query("SELECT currency, balance_minor, reserved_minor FROM credit_wallets WHERE organization_id = ? FOR UPDATE",
                (rs, i) -> new Wallet(rs.getString(1), rs.getLong(2), rs.getLong(3)), orgId);
        if (rows.isEmpty()) throw ApiException.notFound("Wallet");
        return rows.getFirst();
    }

    private boolean exists(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM credit_transactions WHERE idempotency_key = ?)", Boolean.class, key));
    }

    private void ledger(UUID orgId, String type, long amount, long balanceAfter, long reservedAfter, String refType, UUID refId, String key, String description) {
        jdbc.update("""
                INSERT INTO credit_transactions (id, organization_id, type, amount_minor, balance_after, reserved_after, reference_type, reference_id, idempotency_key, description)
                VALUES (?,?,?,?,?,?,?,?,?,?)""", Ids.newId(), orgId, type, amount, balanceAfter, reservedAfter, refType, refId, key, description);
    }
}
