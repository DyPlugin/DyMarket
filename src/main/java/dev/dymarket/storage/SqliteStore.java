package dev.dymarket.storage;

import dev.dymarket.economy.EconomyStore;
import dev.dymarket.economy.Transaction;
import dev.dymarket.economy.TransactionKind;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The SQLite database in one file. Not thread-safe by design: every method runs on the DyMarket-IO thread
 * ({@link IoThread}), which is the only thread that ever holds the connection.
 */
public final class SqliteStore implements EconomyStore {
    public static final int SCHEMA = 1;

    public record TopEntry(UUID id, String name, long balance) { }

    public record PlayerRow(UUID id, String name, String language, long lastSeen) { }

    public record Notice(long id, String key, Map<String, String> values) { }

    private final File file;
    private final Logger logger;
    private Connection connection;

    public SqliteStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** True when the server ships the SQLite driver (Spigot, Paper, Purpur and Folia all do). */
    public static boolean driverPresent() {
        try {
            Class.forName("org.sqlite.JDBC");
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    public void open() throws SQLException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists())
            throw new SQLException("Could not create " + parent);
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            migrate();
        } catch (SQLException failure) {
            close();
            throw failure;
        }
    }

    public void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException exception) {
            logger.warning("Could not close the database: " + exception.getMessage());
        }
        connection = null;
    }

    public boolean isOpen() {
        return connection != null;
    }

    /** The open connection, for module stores (auction, estate). IO thread only. */
    public Connection connection() {
        return connection;
    }

    // ---- schema ---------------------------------------------------------------------------------------------

    private void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)");
        }
        int version = schemaVersion();
        if (version > SCHEMA)
            throw new SQLException("data.db was written by a newer DyMarket (schema " + version + "); update DyMarket.");
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            if (version < 1) {
                statement.execute("CREATE TABLE IF NOT EXISTS accounts(uuid TEXT, name TEXT, currency TEXT, balance INTEGER, updated INTEGER,"
                        + " PRIMARY KEY(uuid, currency))");
                statement.execute("CREATE TABLE IF NOT EXISTS transactions(id TEXT PRIMARY KEY, ts INTEGER, kind TEXT, currency TEXT,"
                        + " from_uuid TEXT, to_uuid TEXT, amount INTEGER, from_after INTEGER, to_after INTEGER, note TEXT,"
                        + " external_id TEXT UNIQUE, reversed_by TEXT)");
                // tax: the seller's tax rate in hundredths of a percent, fixed when the item is listed.
                statement.execute("CREATE TABLE IF NOT EXISTS auction_listings(id TEXT PRIMARY KEY, seller TEXT, seller_name TEXT, item BLOB,"
                        + " amount INTEGER, price INTEGER, currency TEXT, created INTEGER, expires INTEGER, status TEXT, buyer TEXT, tax INTEGER DEFAULT 0)");
                statement.execute("CREATE TABLE IF NOT EXISTS collect_box(id INTEGER PRIMARY KEY, owner TEXT, item BLOB, reason TEXT, ts INTEGER)");
                statement.execute("CREATE TABLE IF NOT EXISTS estate_listings(id TEXT PRIMARY KEY, owner TEXT, owner_name TEXT, world TEXT,"
                        + " chunks TEXT, type TEXT, price INTEGER, currency TEXT, days INTEGER, created INTEGER, expires INTEGER, status TEXT)");
                statement.execute("CREATE TABLE IF NOT EXISTS estate_leases(id TEXT PRIMARY KEY, listing_id TEXT, owner TEXT, tenant TEXT,"
                        + " tenant_name TEXT, world TEXT, chunks TEXT, price INTEGER, currency TEXT, days INTEGER, started INTEGER,"
                        + " paid_until INTEGER, autorenew INTEGER, status TEXT)");
                statement.execute("CREATE TABLE IF NOT EXISTS players(uuid TEXT PRIMARY KEY, name TEXT, language TEXT, last_seen INTEGER)");
                // Messages for players who were offline when something happened (estate rent, expired listings).
                statement.execute("CREATE TABLE IF NOT EXISTS notices(id INTEGER PRIMARY KEY, player TEXT, message TEXT, args TEXT, ts INTEGER)");
                statement.execute("CREATE INDEX IF NOT EXISTS transactions_from ON transactions(from_uuid, ts)");
                statement.execute("CREATE INDEX IF NOT EXISTS transactions_to ON transactions(to_uuid, ts)");
                statement.execute("CREATE INDEX IF NOT EXISTS accounts_top ON accounts(currency, balance)");
                statement.execute("CREATE INDEX IF NOT EXISTS players_name ON players(name COLLATE NOCASE)");
                statement.execute("CREATE INDEX IF NOT EXISTS auction_status ON auction_listings(status, expires)");
                statement.execute("CREATE INDEX IF NOT EXISTS collect_owner ON collect_box(owner)");
                statement.execute("CREATE INDEX IF NOT EXISTS notices_player ON notices(player)");
            }
            // Later schema versions add "if (version < 2) { ... }" here.
            setMeta("schema", String.valueOf(SCHEMA));
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    public int schemaVersion() throws SQLException {
        String value = meta("schema");
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException broken) {
            return 0;
        }
    }

    public String meta(String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    public void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    // ---- backups --------------------------------------------------------------------------------------------

    /**
     * Copies the database to backups/data-&lt;date&gt;.db and keeps the newest {@code keep} copies.
     * Call it before {@link #open()} so the file is complete.
     */
    public static void backup(File database, File folder, LocalDate date, int keep) throws IOException {
        if (!database.isFile()) return;
        Files.createDirectories(folder.toPath());
        String base = database.getName().replaceFirst("\\.db$", "");
        Path target = folder.toPath().resolve(base + "-" + date + ".db");
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.copy(database.toPath(), temporary, StandardCopyOption.REPLACE_EXISTING);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        File[] copies = folder.listFiles((dir, name) -> name.startsWith(base + "-") && name.endsWith(".db"));
        if (copies == null || copies.length <= keep) return;
        java.util.Arrays.sort(copies, java.util.Comparator.comparing(File::getName).reversed());
        for (int i = keep; i < copies.length; i++) Files.deleteIfExists(copies[i].toPath());
    }

    // ---- economy --------------------------------------------------------------------------------------------

    @Override
    public StoredAccount loadAccount(UUID id) throws SQLException {
        Map<String, Long> balances = new HashMap<>();
        String name = null;
        try (PreparedStatement statement = connection.prepareStatement("SELECT name, currency, balance FROM accounts WHERE uuid = ?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (result.getString(1) != null) name = result.getString(1);
                    balances.put(result.getString(2), result.getLong(3));
                }
            }
        }
        PlayerRow player = player(id);
        if (player != null && player.name() != null) name = player.name();
        if (balances.isEmpty() && name == null) return null;
        return new StoredAccount(name, balances);
    }

    @Override
    public boolean externalIdExists(String externalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM transactions WHERE external_id = ?")) {
            statement.setString(1, externalId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    @Override
    public Transaction transaction(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM transactions WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    @Override
    public void save(List<AccountSnapshot> accounts, List<Transaction> transactions, Map<String, String> reversals) throws SQLException {
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        long now = System.currentTimeMillis();
        try (PreparedStatement account = connection.prepareStatement("INSERT INTO accounts(uuid, name, currency, balance, updated)"
                + " VALUES(?, ?, ?, ?, ?) ON CONFLICT(uuid, currency) DO UPDATE SET name = excluded.name, balance = excluded.balance,"
                + " updated = excluded.updated");
             PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO transactions(id, ts, kind, currency,"
                     + " from_uuid, to_uuid, amount, from_after, to_after, note, external_id, reversed_by) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement reverse = connection.prepareStatement("UPDATE transactions SET reversed_by = ? WHERE id = ? AND reversed_by IS NULL")) {
            for (AccountSnapshot snapshot : accounts) {
                for (Map.Entry<String, Long> balance : snapshot.balances().entrySet()) {
                    account.setString(1, snapshot.id().toString());
                    account.setString(2, snapshot.name());
                    account.setString(3, balance.getKey());
                    account.setLong(4, balance.getValue());
                    account.setLong(5, now);
                    account.addBatch();
                }
            }
            account.executeBatch();
            for (Transaction transaction : transactions) {
                insert.setString(1, transaction.id());
                insert.setLong(2, transaction.timestamp());
                insert.setString(3, transaction.kind().name());
                insert.setString(4, transaction.currency());
                uuid(insert, 5, transaction.from());
                uuid(insert, 6, transaction.to());
                insert.setLong(7, transaction.amount());
                insert.setLong(8, transaction.fromAfter());
                insert.setLong(9, transaction.toAfter());
                insert.setString(10, transaction.note());
                insert.setString(11, transaction.externalId());
                insert.setString(12, transaction.reversedBy());
                if (insert.executeUpdate() == 0) logger.warning("Transaction " + transaction.id() + " was already stored; skipped.");
            }
            for (Map.Entry<String, String> mark : reversals.entrySet()) {
                reverse.setString(1, mark.getValue());
                reverse.setString(2, mark.getKey());
                reverse.addBatch();
            }
            reverse.executeBatch();
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    /** A player's transactions, newest first. */
    public List<Transaction> transactions(UUID player, int offset, int limit) throws SQLException {
        List<Transaction> list = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM transactions WHERE from_uuid = ? OR to_uuid = ? ORDER BY ts DESC, id LIMIT ? OFFSET ?")) {
            statement.setString(1, player.toString());
            statement.setString(2, player.toString());
            statement.setInt(3, limit);
            statement.setInt(4, offset);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) list.add(read(result));
            }
        }
        return list;
    }

    public int transactionCount(UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM transactions WHERE from_uuid = ? OR to_uuid = ?")) {
            statement.setString(1, player.toString());
            statement.setString(2, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    /** The highest balances of a currency, skipping the given players. */
    public List<TopEntry> top(String currency, int limit, java.util.Set<UUID> skip) throws SQLException {
        List<TopEntry> list = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT a.uuid, COALESCE(p.name, a.name), a.balance FROM accounts a"
                + " LEFT JOIN players p ON p.uuid = a.uuid WHERE a.currency = ? AND a.balance > 0 ORDER BY a.balance DESC, a.uuid LIMIT ?")) {
            statement.setString(1, currency);
            statement.setInt(2, limit + skip.size());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next() && list.size() < limit) {
                    UUID id = UUID.fromString(result.getString(1));
                    if (!skip.contains(id)) list.add(new TopEntry(id, result.getString(2), result.getLong(3)));
                }
            }
        }
        return list;
    }

    /** Every stored balance of a currency, for importers that must not overwrite. */
    public Map<UUID, Long> balances(String currency) throws SQLException {
        Map<UUID, Long> map = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, balance FROM accounts WHERE currency = ?")) {
            statement.setString(1, currency);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) map.put(UUID.fromString(result.getString(1)), result.getLong(2));
            }
        }
        return map;
    }

    // ---- players --------------------------------------------------------------------------------------------

    public void upsertPlayer(UUID id, String name, long lastSeen) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO players(uuid, name, last_seen) VALUES(?, ?, ?)"
                + " ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen")) {
            statement.setString(1, id.toString());
            statement.setString(2, name);
            statement.setLong(3, lastSeen);
            statement.executeUpdate();
        }
    }

    public PlayerRow player(UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, name, language, last_seen FROM players WHERE uuid = ?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new PlayerRow(id, result.getString(2), result.getString(3), result.getLong(4)) : null;
            }
        }
    }

    /** A player by name, ignoring ASCII case; the most recently seen wins when names were reused. */
    public PlayerRow playerByName(String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid, name, language, last_seen FROM players WHERE name = ? COLLATE NOCASE ORDER BY last_seen DESC LIMIT 1")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new PlayerRow(UUID.fromString(result.getString(1)), result.getString(2), result.getString(3),
                        result.getLong(4)) : null;
            }
        }
    }

    public void setLanguage(UUID id, String language) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO players(uuid, language, last_seen) VALUES(?, ?, ?)"
                + " ON CONFLICT(uuid) DO UPDATE SET language = excluded.language")) {
            statement.setString(1, id.toString());
            if ("auto".equals(language) || language == null) statement.setNull(2, Types.VARCHAR);
            else statement.setString(2, language);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    /**
     * Moves everything a player owns to another player after an account merge: auction listings, the collect
     * box, estate listings and leases. The source keeps a note in players.name.
     */
    public void mergeOwnership(UUID from, UUID to, String toName) throws SQLException {
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            update("UPDATE auction_listings SET seller = ?, seller_name = COALESCE(?, seller_name) WHERE seller = ?", to, toName, from);
            update("UPDATE auction_listings SET buyer = ? WHERE buyer = ?", to, null, from);
            update("UPDATE collect_box SET owner = ? WHERE owner = ?", to, null, from);
            update("UPDATE estate_listings SET owner = ?, owner_name = COALESCE(?, owner_name) WHERE owner = ?", to, toName, from);
            update("UPDATE estate_leases SET owner = ? WHERE owner = ?", to, null, from);
            update("UPDATE estate_leases SET tenant = ?, tenant_name = COALESCE(?, tenant_name) WHERE tenant = ?", to, toName, from);
            update("UPDATE notices SET player = ? WHERE player = ?", to, null, from);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE players SET name = COALESCE(name, '') || ' (merged into ' || ? || ')' WHERE uuid = ?")) {
                statement.setString(1, toName == null ? to.toString() : toName);
                statement.setString(2, from.toString());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    /** Runs "SET ... = to [, name = ?] WHERE ... = from"; the name parameter is only bound when the SQL has three. */
    private void update(String sql, UUID to, String name, UUID from) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            long count = sql.chars().filter(c -> c == '?').count();
            statement.setString(1, to.toString());
            if (count == 3) {
                statement.setString(2, name);
                statement.setString(3, from.toString());
            } else {
                statement.setString(2, from.toString());
            }
            statement.executeUpdate();
        }
    }

    // ---- notices --------------------------------------------------------------------------------------------

    public void addNotice(UUID player, String key, Map<String, String> values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO notices(player, message, args, ts) VALUES(?, ?, ?, ?)")) {
            statement.setString(1, player.toString());
            statement.setString(2, key);
            statement.setString(3, encode(values));
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    /** Returns and deletes a player's waiting notices, oldest first. */
    public List<Notice> takeNotices(UUID player) throws SQLException {
        List<Notice> list = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, message, args FROM notices WHERE player = ? ORDER BY id")) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) list.add(new Notice(result.getLong(1), result.getString(2), decode(result.getString(3))));
            }
        }
        if (!list.isEmpty()) try (PreparedStatement statement = connection.prepareStatement("DELETE FROM notices WHERE player = ?")) {
            statement.setString(1, player.toString());
            statement.executeUpdate();
        }
        return list;
    }

    static String encode(Map<String, String> values) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!text.isEmpty()) text.append('\u001e');
            text.append(entry.getKey()).append('\u001f').append(entry.getValue() == null ? "" : entry.getValue());
        }
        return text.toString();
    }

    static Map<String, String> decode(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return values;
        for (String pair : text.split("\u001e")) {
            int split = pair.indexOf('\u001f');
            if (split > 0) values.put(pair.substring(0, split), pair.substring(split + 1));
        }
        return values;
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static void uuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value.toString());
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Transaction read(ResultSet result) throws SQLException {
        return new Transaction(result.getString("id"), result.getLong("ts"), TransactionKind.parse(result.getString("kind")),
                result.getString("currency"), uuid(result.getString("from_uuid")), uuid(result.getString("to_uuid")),
                result.getLong("amount"), result.getLong("from_after"), result.getLong("to_after"), result.getString("note"),
                result.getString("external_id"), result.getString("reversed_by"));
    }
}
