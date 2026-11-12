package dev.dymarket.estate;

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
 * Estate listings and leases in DyMarket's SQLite file (tables {@code estate_listings} and {@code estate_leases}).
 * Uses the shared connection, so every method must run on the DyMarket-IO thread.
 */
public final class EstateStore implements EstateService.Storage {
    private static final String LISTING_COLUMNS = "id, owner, owner_name, world, chunks, type, price, currency, days, created, expires, status, tax";
    private static final String LEASE_COLUMNS = "id, listing_id, owner, owner_name, tenant, tenant_name, world, chunks, price, currency, days,"
            + " started, paid_until, autorenew, evicted, tax, status";

    private final Supplier<Connection> connection;

    public EstateStore(SqliteStore store) {
        this(store::connection);
    }

    public EstateStore(Supplier<Connection> connection) {
        this.connection = connection;
    }

    private Connection db() throws SQLException {
        Connection open = connection.get();
        if (open == null) throw new SQLException("The database is closed");
        return open;
    }

    /** Adds the columns the estate module needs beyond the first schema (tax rate, owner name, eviction flag). */
    public void prepare() throws SQLException {
        addColumn("estate_listings", "tax", "INTEGER DEFAULT 0");
        addColumn("estate_leases", "tax", "INTEGER DEFAULT 0");
        addColumn("estate_leases", "owner_name", "TEXT");
        addColumn("estate_leases", "evicted", "INTEGER DEFAULT 0");
        try (Statement statement = db().createStatement()) {
            statement.execute("CREATE INDEX IF NOT EXISTS estate_listings_status ON estate_listings(status)");
            statement.execute("CREATE INDEX IF NOT EXISTS estate_leases_status ON estate_leases(status)");
        }
    }

    private void addColumn(String table, String column, String type) throws SQLException {
        boolean found = false;
        try (Statement statement = db().createStatement(); ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) if (column.equalsIgnoreCase(result.getString("name"))) found = true;
        }
        if (!found) try (Statement statement = db().createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    // ---- listings -------------------------------------------------------------------------------------------

    @Override
    public List<EstateListing> loadListings() throws SQLException {
        List<EstateListing> list = new ArrayList<>();
        try (PreparedStatement statement = db().prepareStatement("SELECT " + LISTING_COLUMNS + " FROM estate_listings WHERE status IN (?, ?) ORDER BY created")) {
            statement.setString(1, EstateListing.Status.ACTIVE.name());
            statement.setString(2, EstateListing.Status.LEASED.name());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) list.add(readListing(result));
            }
        }
        return list;
    }

    /** One listing in any status, or null. */
    public EstateListing findListing(String id) throws SQLException {
        try (PreparedStatement statement = db().prepareStatement("SELECT " + LISTING_COLUMNS + " FROM estate_listings WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readListing(result) : null;
            }
        }
    }

    private static EstateListing readListing(ResultSet result) throws SQLException {
        return new EstateListing(result.getString(1), UUID.fromString(result.getString(2)), result.getString(3), result.getString(4),
                ChunkPos.decode(result.getString(5)), EstateListing.Type.parse(result.getString(6)), result.getLong(7), result.getString(8),
                result.getInt(9), result.getLong(10), result.getLong(11), result.getInt(13), EstateListing.Status.parse(result.getString(12)));
    }

    @Override
    public void insertListing(EstateListing listing) throws SQLException {
        try (PreparedStatement statement = db().prepareStatement("INSERT INTO estate_listings(" + LISTING_COLUMNS
                + ") VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, listing.id());
            statement.setString(2, listing.owner().toString());
            statement.setString(3, listing.ownerName());
            statement.setString(4, listing.world());
            statement.setString(5, ChunkPos.encode(listing.chunks()));
            statement.setString(6, listing.type().name());
            statement.setLong(7, listing.price());
            statement.setString(8, listing.currency());
            statement.setInt(9, listing.days());
            statement.setLong(10, listing.created());
            statement.setLong(11, listing.expires());
            statement.setString(12, listing.status().name());
            statement.setInt(13, listing.taxBasisPoints());
            statement.executeUpdate();
        }
    }

    @Override
    public boolean transitionListing(String id, EstateListing.Status from, EstateListing.Status to) throws SQLException {
        try (PreparedStatement statement = db().prepareStatement("UPDATE estate_listings SET status = ? WHERE id = ? AND status = ?")) {
            statement.setString(1, to.name());
            statement.setString(2, id);
            statement.setString(3, from.name());
            return statement.executeUpdate() == 1;
        }
    }

    // ---- leases ---------------------------------------------------------------------------------------------

    @Override
    public List<Lease> loadLeases() throws SQLException {
        List<Lease> list = new ArrayList<>();
        try (PreparedStatement statement = db().prepareStatement("SELECT " + LEASE_COLUMNS + " FROM estate_leases WHERE status IN (?, ?) ORDER BY started")) {
            statement.setString(1, Lease.Status.ACTIVE.name());
            statement.setString(2, Lease.Status.GRACE.name());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) list.add(readLease(result));
            }
        }
        return list;
    }

    /** One lease in any status, or null. */
    public Lease findLease(String id) throws SQLException {
        try (PreparedStatement statement = db().prepareStatement("SELECT " + LEASE_COLUMNS + " FROM estate_leases WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readLease(result) : null;
            }
        }
    }

    private static Lease readLease(ResultSet result) throws SQLException {
        return new Lease(result.getString(1), result.getString(2), UUID.fromString(result.getString(3)), result.getString(4),
                UUID.fromString(result.getString(5)), result.getString(6), result.getString(7), ChunkPos.decode(result.getString(8)),
                result.getLong(9), result.getString(10), result.getInt(11), result.getLong(12), result.getLong(13), result.getInt(14) != 0,
                result.getInt(15) != 0, result.getInt(16), Lease.Status.parse(result.getString(17)));
    }

    @Override
    public void insertLease(Lease lease) throws SQLException {
        try (PreparedStatement statement = db().prepareStatement("INSERT INTO estate_leases(" + LEASE_COLUMNS
                + ") VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, lease.id());
            statement.setString(2, lease.listingId());
            statement.setString(3, lease.owner().toString());
            statement.setString(4, lease.ownerName());
            statement.setString(5, lease.tenant().toString());
            statement.setString(6, lease.tenantName());
            statement.setString(7, lease.world());
            statement.setString(8, ChunkPos.encode(lease.chunks()));
            statement.setLong(9, lease.price());
            statement.setString(10, lease.currency());
            statement.setInt(11, lease.days());
            statement.setLong(12, lease.started());
            statement.setLong(13, lease.paidUntil());
            statement.setInt(14, lease.autorenew() ? 1 : 0);
            statement.setInt(15, lease.evicted() ? 1 : 0);
            statement.setInt(16, lease.taxBasisPoints());
            statement.setString(17, lease.status().name());
            statement.executeUpdate();
        }
    }

    @Override
    public void updateLease(Lease lease) throws SQLException {
        try (PreparedStatement statement = db().prepareStatement(
                "UPDATE estate_leases SET paid_until = ?, autorenew = ?, evicted = ?, status = ? WHERE id = ?")) {
            statement.setLong(1, lease.paidUntil());
            statement.setInt(2, lease.autorenew() ? 1 : 0);
            statement.setInt(3, lease.evicted() ? 1 : 0);
            statement.setString(4, lease.status().name());
            statement.setString(5, lease.id());
            if (statement.executeUpdate() != 1) throw new SQLException("Lease " + lease.id() + " is not stored");
        }
    }
}
