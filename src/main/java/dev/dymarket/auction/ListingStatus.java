package dev.dymarket.auction;

/** Where a listing is in its life: only ACTIVE listings are shown and can be bought, cancelled or expire. */
public enum ListingStatus {
    ACTIVE, SOLD, CANCELLED, EXPIRED, REMOVED;

    public static ListingStatus parse(String value) {
        try {
            return valueOf(value);
        } catch (RuntimeException unknown) {
            return REMOVED;
        }
    }
}
