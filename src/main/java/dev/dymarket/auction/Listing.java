package dev.dymarket.auction;

import java.util.UUID;

/**
 * One buy-it-now listing. The item is kept as the bytes from {@code ItemCodec}. The status only changes while
 * holding this object's monitor (the per-listing lock in {@link AuctionService}); storage is updated afterwards
 * with a conditional write, so the database stays the final word on who got the item.
 */
public final class Listing {
    private final String id;
    private volatile UUID seller;
    private volatile String sellerName;
    private final byte[] item;
    private final int amount;
    private final long price;
    private final String currency;
    private final long created;
    private final long expires;
    /** Tax taken from the seller on sale, in hundredths of a percent (500 = 5%), fixed when the item is listed. */
    private final int taxBasisPoints;
    private volatile ListingStatus status;
    private volatile UUID buyer;
    /** Display data filled from the item by the server side: material id, a readable name and folded search text. */
    private volatile String material = "";
    private volatile String itemName = "";
    private volatile String search = "";
    /** The decoded item, cached for the GUI (an ItemStack on the server; never shared without cloning). */
    private volatile Object icon;

    public Listing(String id, UUID seller, String sellerName, byte[] item, int amount, long price, String currency,
                   long created, long expires, int taxBasisPoints, ListingStatus status, UUID buyer) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.item = item;
        this.amount = amount;
        this.price = price;
        this.currency = currency;
        this.created = created;
        this.expires = expires;
        this.taxBasisPoints = taxBasisPoints;
        this.status = status;
        this.buyer = buyer;
    }

    public String id() { return id; }
    public UUID seller() { return seller; }
    public String sellerName() { return sellerName == null ? seller.toString().substring(0, 8) : sellerName; }
    /** The stored item bytes; callers must not change the array. */
    public byte[] item() { return item; }
    public int amount() { return amount; }
    /** Price in minor units of {@link #currency()}. */
    public long price() { return price; }
    public String currency() { return currency; }
    public long created() { return created; }
    public long expires() { return expires; }
    public int taxBasisPoints() { return taxBasisPoints; }
    public ListingStatus status() { return status; }
    public UUID buyer() { return buyer; }
    public String material() { return material; }
    public String itemName() { return itemName.isEmpty() ? material : itemName; }
    public String search() { return search; }
    public Object icon() { return icon; }

    /** The tax for a sale at this listing's price, rounded down. */
    public long tax() {
        return java.math.BigInteger.valueOf(price).multiply(java.math.BigInteger.valueOf(taxBasisPoints))
                .divide(java.math.BigInteger.valueOf(10_000)).longValue();
    }

    public boolean expired(long now) {
        return now >= expires;
    }

    void status(ListingStatus status) { this.status = status; }
    void buyer(UUID buyer) { this.buyer = buyer; }
    void seller(UUID seller, String name) {
        this.seller = seller;
        if (name != null) this.sellerName = name;
    }

    public void describe(String material, String itemName, String search) {
        this.material = material == null ? "" : material;
        this.itemName = itemName == null ? "" : itemName;
        this.search = search == null ? "" : search;
    }

    public void icon(Object icon) { this.icon = icon; }

    @Override
    public String toString() {
        return "Listing#" + id + "(" + status + ")";
    }
}
