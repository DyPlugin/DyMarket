package dev.dymarket.auction;

import dev.dymarket.storage.SqliteStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Auction listings and collection boxes in DyMarket's SQLite file (tables {@code auction_listings} and
 * {@code collect_box}). Uses the shared connection, so every method must run on the DyMarket-IO thread.
 */
public final class AuctionStore implements AuctionService.Storage {
    private static final String COLUMNS = "id, seller, seller_name, item, amount, price, currency, created, expires, status, buyer, tax";

    private final Supplier<Connection> connection;

    public AuctionStore(SqliteStore store) {
        this(store::connection);
    }

    public AuctionStore(Supplier<Connection> connection) {
        this.connection = connection;
    }

    private Connection db() throws SQLException {
        Connection open = connection.get();
        if (open == null) throw new SQLException("The database is closed");
        return open;
    }

    /** Adds the tax column to databases created before it existed. */
    public void prepare() throws SQLException {
        boolean found = false;
        try (Statement statement = db().createStatement(); ResultSet result = statement.executeQuery("PRAGMA table_info(auction_listings)")) {
            while (result.next()) if ("tax".equalsIgnoreCase(result.getString("name"))) found = true;
        }
        if (!found) try (Statement statement = db().createStatement()) {
            statement.execute("ALTER TABLE auction_listings ADD COLUMN tax INTEGER DEFAULT 0");
        }
    }

    @Override
    public List<Listing> loadActive() throws SQLException {
        List<Listing> list = new ArrayList<>();
        try (PreparedStatement statement = db().prepareStatement("SELECT " + COLUMNS + " FROM auction_listings WHERE status = ? ORDER BY created")) {
            statement.setString(1, ListingStatus.ACTIVE.name());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) list.add(read(result));
            }
        }
        return list;
    }

    /** One listing in any status, or null. */
    public Listing find(String id) throws SQLException {
        try (PreparedStatement statement = db().prepareStatement("SELECT " + COLUMNS + " FROM auction_listings WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    private static Listing read(ResultSet result) throws SQLException {
        String buyer = result.getString(11);
        return new Listing(result.getString(1), UUID.fromString(result.getString(2)), result.getString(3), result.getBytes(4),
                result.getInt(5), result.getLong(6), result.getString(7), result.getLong(8), result.getLong(9), result.getInt(12),
                ListingStatus.parse(result.getString(10)), buyer == null ? null : UUID.fromString(buyer));
    }

    @Override
    public void insert(Listing listing) throws SQLException {
        try (PreparedStatement statement = db().prepareStatement("INSERT INTO auction_listings(" + COLUMNS + ") VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, listing.id());
            statement.setString(2, listing.seller().toString());
            statement.setString(3, listing.sellerName());
            statement.setBytes(4, listing.item());
            statement.setInt(5, listing.amount());
            statement.setLong(6, listing.price());
            statement.setString(7, listing.currency());
            statement.setLong(8, listing.created());
            statement.setLong(9, listing.expires());
            statement.setString(10, listing.status().name());
            statement.setString(11, listing.buyer() == null ? null : listing.buyer().toString());
            statement.setInt(12, listing.taxBasisPoints());
            statement.executeUpdate();
        }
    }

    @Override
    public boolean transition(String id, ListingStatus from, ListingStatus to, UUID buyer) throws SQLException {
        try (PreparedStatement statement = db().prepareStatement("UPDATE auction_listings SET status = ?, buyer = ? WHERE id = ? AND status = ?")) {
            statement.setString(1, to.name());
            statement.setString(2, buyer == null ? null : buyer.toString());
            statement.setString(3, id);
            statement.setString(4, from.name());
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public boolean transitionToCollect(String id, ListingStatus to, UUID owner, String reason, long timestamp) throws SQLException {
        Connection db = db();
        boolean auto = db.getAutoCommit();
        db.setAutoCommit(false);
        try {
            if (!transition(id, ListingStatus.ACTIVE, to, null)) {
                db.rollback();
                return false;
            }
            try (PreparedStatement statement = db.prepareStatement(
                    "INSERT INTO collect_box(owner, item, reason, ts) SELECT ?, item, ?, ? FROM auction_listings WHERE id = ?")) {
                statement.setString(1, owner.toString());
                statement.setString(2, reason);
                statement.setLong(3, timestamp);
                statement.setString(4, id);
                if (statement.executeUpdate() != 1) throw new SQLException("Listing " + id + " has no item");
            }
            db.commit();
            return true;
        } catch (SQLException failure) {
            db.rollback();
            throw failure;
        } finally {
            db.setAutoCommit(auto);
        }
    }

    @Override
    public long addCollect(UUID owner, byte[] item, String reason, long timestamp) throws SQLException {
        try (PreparedStatement statement = db().prepareStatement("INSERT INTO collect_box(owner, item, reason, ts) VALUES(?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, owner.toString());
            statement.setBytes(2, item);
            statement.setString(3, reason);
            statement.setLong(4, timestamp);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }
    }

    @Override
    public List<CollectEntry> collectBox(UUID owner) throws SQLException {
        List<CollectEntry> list = new ArrayList<>();
        try (PreparedStatement statement = db().prepareStatement("SELECT id, owner, item, reason, ts FROM collect_box WHERE owner = ? ORDER BY id")) {
            statement.setString(1, owner.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) list.add(readEntry(result));
            }
        }
        return list;
    }

    private static CollectEntry readEntry(ResultSet result) throws SQLException {
        return new CollectEntry(result.getLong(1), UUID.fromString(result.getString(2)), result.getBytes(3), result.getString(4), result.getLong(5));
    }

    @Override
    public int collectCount(UUID owner) throws SQLException {
        try (PreparedStatement statement = db().prepareStatement("SELECT COUNT(*) FROM collect_box WHERE owner = ?")) {
            statement.setString(1, owner.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    @Override
    public CollectEntry takeCollect(long id, UUID owner) throws SQLException {
        CollectEntry entry = null;
        try (PreparedStatement statement = db().prepareStatement("SELECT id, owner, item, reason, ts FROM collect_box WHERE id = ? AND owner = ?")) {
            statement.setLong(1, id);
            statement.setString(2, owner.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) entry = readEntry(result);
            }
        }
        if (entry == null) return null;
        try (PreparedStatement statement = db().prepareStatement("DELETE FROM collect_box WHERE id = ? AND owner = ?")) {
            statement.setLong(1, id);
            statement.setString(2, owner.toString());
            return statement.executeUpdate() == 1 ? entry : null;
        }
    }

    @Override
    public List<CollectEntry> takeAllCollect(UUID owner) throws SQLException {
        Connection db = db();
        boolean auto = db.getAutoCommit();
        db.setAutoCommit(false);
        try {
            List<CollectEntry> entries = collectBox(owner);
            try (PreparedStatement statement = db.prepareStatement("DELETE FROM collect_box WHERE owner = ?")) {
                statement.setString(1, owner.toString());
                statement.executeUpdate();
            }
            db.commit();
            return entries;
        } catch (SQLException failure) {
            db.rollback();
            throw failure;
        } finally {
            db.setAutoCommit(auto);
        }
    }
}
