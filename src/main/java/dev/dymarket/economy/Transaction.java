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

    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE = new java.util.concurrent.atomic.AtomicLong();

    /** Continues numbering after the highest stored id (start-up). */
    public static void seed(long last) {
        SEQUENCE.accumulateAndGet(last, Math::max);
    }

    /** The next transaction number, short and easy to type in /dyma rollback. */
    public static String newId() {
        return Long.toString(SEQUENCE.incrementAndGet());
    }
}
