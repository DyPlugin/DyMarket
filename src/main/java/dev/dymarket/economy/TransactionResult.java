package dev.dymarket.economy;

import java.util.List;

/** The outcome of an economy operation; {@code transactions} lists what was logged (empty on failure). */
public record TransactionResult(Status status, List<Transaction> transactions, long balance) {
    public enum Status {
        SUCCESS, INSUFFICIENT_FUNDS, MAX_BALANCE, INVALID_AMOUNT, UNKNOWN_CURRENCY, NOT_TRANSFERABLE, SAME_ACCOUNT,
        ALREADY_APPLIED, NOT_FOUND, ALREADY_REVERSED, CANCELLED, RATE_LIMITED, UNAVAILABLE, FAILED
    }

    public static TransactionResult of(Status status) {
        return new TransactionResult(status, List.of(), 0);
    }

    public static TransactionResult of(Status status, long balance) {
        return new TransactionResult(status, List.of(), balance);
    }

    public static TransactionResult success(long balance, List<Transaction> transactions) {
        return new TransactionResult(Status.SUCCESS, List.copyOf(transactions), balance);
    }

    public boolean success() {
        return status == Status.SUCCESS;
    }

    /** The first logged transaction, or null. */
    public Transaction transaction() {
        return transactions.isEmpty() ? null : transactions.get(0);
    }
}
