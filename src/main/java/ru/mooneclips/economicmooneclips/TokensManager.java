package ru.mooneclips.economicmooneclips;

import org.bukkit.OfflinePlayer;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TokensManager {
    public record TopEntry(String name, long balance) {}

    private final Connection connection;

    public TokensManager(File dataFolder) throws SQLException {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new SQLException("Cannot create plugin data folder");
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + new File(dataFolder, "tokens.db").getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("PRAGMA journal_mode=WAL");
            st.executeUpdate("PRAGMA busy_timeout=5000");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS token_accounts (" +
                    "uuid TEXT PRIMARY KEY, name TEXT NOT NULL, balance INTEGER NOT NULL DEFAULT 0 CHECK(balance >= 0))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS token_transactions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, actor TEXT NOT NULL, " +
                    "amount INTEGER NOT NULL, operation TEXT NOT NULL, note TEXT NOT NULL, created_at INTEGER NOT NULL)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_token_transactions_uuid ON token_transactions(uuid)");
        }
    }

    public synchronized long getBalance(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT balance FROM token_accounts WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read token balance", ex);
        }
    }

    public synchronized boolean deposit(OfflinePlayer player, long amount, String actor, String note) {
        if (player == null || amount <= 0) return false;
        return inTransaction(() -> {
            ensureAccount(player);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE token_accounts SET balance=balance+?, name=? WHERE uuid=? AND balance <= ?")) {
                ps.setLong(1, amount);
                ps.setString(2, safeName(player));
                ps.setString(3, player.getUniqueId().toString());
                ps.setLong(4, Long.MAX_VALUE - amount);
                if (ps.executeUpdate() != 1) return false;
            }
            log(player.getUniqueId(), actor, amount, "CREDIT", note);
            return true;
        });
    }

    public synchronized boolean withdraw(OfflinePlayer player, long amount, String actor, String note) {
        if (player == null || amount <= 0) return false;
        return inTransaction(() -> {
            ensureAccount(player);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE token_accounts SET balance=balance-?, name=? WHERE uuid=? AND balance>=?")) {
                ps.setLong(1, amount);
                ps.setString(2, safeName(player));
                ps.setString(3, player.getUniqueId().toString());
                ps.setLong(4, amount);
                if (ps.executeUpdate() != 1) return false;
            }
            log(player.getUniqueId(), actor, -amount, "DEBIT", note);
            return true;
        });
    }

    public synchronized boolean setBalance(OfflinePlayer player, long balance, String actor) {
        if (player == null || balance < 0) return false;
        return inTransaction(() -> {
            ensureAccount(player);
            long old = getBalance(player.getUniqueId());
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE token_accounts SET balance=?, name=? WHERE uuid=?")) {
                ps.setLong(1, balance);
                ps.setString(2, safeName(player));
                ps.setString(3, player.getUniqueId().toString());
                ps.executeUpdate();
            }
            long delta = balance - old;
            if (delta != 0) log(player.getUniqueId(), actor, delta, "SET", "balance set");
            return true;
        });
    }

    public synchronized boolean transfer(OfflinePlayer from, OfflinePlayer to, long amount) {
        if (from == null || to == null || amount <= 0 || from.getUniqueId().equals(to.getUniqueId())) return false;
        return inTransaction(() -> {
            ensureAccount(from);
            ensureAccount(to);
            try (PreparedStatement debit = connection.prepareStatement(
                        "UPDATE token_accounts SET balance=balance-? WHERE uuid=? AND balance>=?");
                 PreparedStatement credit = connection.prepareStatement(
                        "UPDATE token_accounts SET balance=balance+? WHERE uuid=? AND balance<=?")) {
                debit.setLong(1, amount);
                debit.setString(2, from.getUniqueId().toString());
                debit.setLong(3, amount);
                if (debit.executeUpdate() != 1) return false;
                credit.setLong(1, amount);
                credit.setString(2, to.getUniqueId().toString());
                credit.setLong(3, Long.MAX_VALUE - amount);
                if (credit.executeUpdate() != 1) throw new SQLException("Recipient token balance overflow");
            }
            log(from.getUniqueId(), from.getName(), -amount, "TRANSFER_OUT", "to " + safeName(to));
            log(to.getUniqueId(), from.getName(), amount, "TRANSFER_IN", "from " + safeName(from));
            return true;
        });
    }

    public synchronized List<TopEntry> getTop(int limit) {
        List<TopEntry> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name,balance FROM token_accounts WHERE balance>0 ORDER BY balance DESC, name COLLATE NOCASE ASC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 100)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new TopEntry(rs.getString(1), rs.getLong(2)));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read token leaderboard", ex);
        }
        return result;
    }

    private void ensureAccount(OfflinePlayer player) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO token_accounts(uuid,name,balance) VALUES(?,?,0) " +
                "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name")) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, safeName(player));
            ps.executeUpdate();
        }
    }

    private void log(UUID uuid, String actor, long amount, String operation, String note) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO token_transactions(uuid,actor,amount,operation,note,created_at) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, actor == null ? "system" : actor);
            ps.setLong(3, amount);
            ps.setString(4, operation);
            ps.setString(5, note == null ? "" : note);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private String safeName(OfflinePlayer player) {
        String name = player.getName();
        return name == null ? player.getUniqueId().toString() : name;
    }

    private boolean inTransaction(SqlWork work) {
        try {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                boolean result = work.run();
                connection.commit();
                return result;
            } catch (Exception ex) {
                connection.rollback();
                if (ex instanceof SQLException sql) throw sql;
                throw new SQLException(ex);
            } finally {
                connection.setAutoCommit(previous);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Token transaction failed", ex);
        }
    }

    public synchronized void close() {
        try { connection.close(); }
        catch (SQLException ex) { throw new IllegalStateException("Cannot close token database", ex); }
    }

    @FunctionalInterface
    private interface SqlWork { boolean run() throws Exception; }
}
