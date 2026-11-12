package dev.dymarket.economy;

import java.util.UUID;

/**
 * One line of the append-only log. Money moves from {@code from} to {@code to}; a null side means money entering
 * (given, bought with credits) or leaving (taken, taxes) the economy. Amounts are minor units and never negative.
 */
public record Transaction(String id, long timestamp, TransactionKind kind, String currency, UUID from, UUID to,
                          long amount, long fromAfter, long toAfter, String note, String externalId, String reversedBy) {

    public Transaction withReversedBy(String reversal) {
        return new Transaction(id, timestamp, kind, currency, from, to, amount, fromAfter, toAfter, note, externalId, reversal);
    }

    /** A short random id, easy to type in /dymarket rollback. */
    public static String newId() {
        String text = UUID.randomUUID().toString().replace("-", "");
        return text.substring(0, 12);
    }
}
