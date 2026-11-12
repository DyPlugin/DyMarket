package dev.dymarket.economy;

/** Why a balance changed; stored in the transaction log. */
public enum TransactionKind {
    PAY, GIVE, TAKE, SET, EXCHANGE, AH_SALE, AH_TAX, ESTATE_SALE, ESTATE_RENT, REFUND, ROLLBACK, MERGE, IMPORT, TOPUP, BONUS, VAULT;

    public static TransactionKind parse(String value) {
        try {
            return valueOf(value);
        } catch (RuntimeException unknown) {
            return VAULT;
        }
    }
}
