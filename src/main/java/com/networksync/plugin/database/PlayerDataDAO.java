package com.networksync.plugin.database;

import com.networksync.plugin.model.BackupEntry;
import com.networksync.plugin.model.PlayerData;
import com.networksync.plugin.util.ItemSerializer;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Couche d'accès aux données. Toutes les méthodes sont bloquantes (JDBC classique)
 * et doivent donc être appelées depuis un thread asynchrone.
 */
public class PlayerDataDAO {

    private final DatabaseManager db;

    public PlayerDataDAO(DatabaseManager db) {
        this.db = db;
    }

    // =========================================================
    //  players
    // =========================================================

    public void upsertPlayer(UUID uuid, String username, String server, long revision) throws SQLException {
        String sql = """
            INSERT INTO players (uuid, username, last_server, last_seen, revision, version)
            VALUES (?, ?, ?, ?, ?, 1)
            ON DUPLICATE KEY UPDATE username = VALUES(username),
                                     last_server = VALUES(last_server),
                                     last_seen = VALUES(last_seen),
                                     revision = VALUES(revision)
            """;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setString(3, server);
            ps.setLong(4, System.currentTimeMillis());
            ps.setLong(5, revision);
            ps.executeUpdate();
        }
    }

    public long getKnownRevision(UUID uuid) throws SQLException {
        String sql = "SELECT revision FROM players WHERE uuid = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    // =========================================================
    //  inventories (état courant "current_state")
    // =========================================================

    /**
     * Sauvegarde l'état courant du joueur, avec protection anti-duplication :
     * l'écriture est refusée si la revision fournie n'est pas strictement supérieure
     * à celle déjà connue en base.
     *
     * @return true si l'écriture a été acceptée, false si elle a été rejetée (revision périmée).
     */
    public boolean saveCurrentState(PlayerData data) throws SQLException {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                long known = getKnownRevisionForUpdate(c, data.getUuid());
                if (data.getRevision() <= known && known != 0) {
                    c.rollback();
                    return false; // ancienne version -> refusée
                }

                upsertPlayerTx(c, data);
                upsertInventoryTx(c, data);
                upsertEconomyTx(c, data);

                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private long getKnownRevisionForUpdate(Connection c, UUID uuid) throws SQLException {
        String sql = "SELECT revision FROM players WHERE uuid = ? FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private void upsertPlayerTx(Connection c, PlayerData data) throws SQLException {
        String sql = """
            INSERT INTO players (uuid, username, last_server, last_seen, revision, version)
            VALUES (?, ?, ?, ?, ?, 1)
            ON DUPLICATE KEY UPDATE username = VALUES(username),
                                     last_server = VALUES(last_server),
                                     last_seen = VALUES(last_seen),
                                     revision = VALUES(revision)
            """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, data.getUuid().toString());
            ps.setString(2, data.getUsername());
            ps.setString(3, data.getServer());
            ps.setLong(4, System.currentTimeMillis());
            ps.setLong(5, data.getRevision());
            ps.executeUpdate();
        }
    }

    private void upsertInventoryTx(Connection c, PlayerData data) throws SQLException {
        String sql = """
            INSERT INTO inventories (uuid, inventory_data, armor_data, offhand_data, enderchest_data,
                                      xp, level, health, food, saturation, potion_effects, revision, server, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE inventory_data = VALUES(inventory_data),
                                     armor_data = VALUES(armor_data),
                                     offhand_data = VALUES(offhand_data),
                                     enderchest_data = VALUES(enderchest_data),
                                     xp = VALUES(xp),
                                     level = VALUES(level),
                                     health = VALUES(health),
                                     food = VALUES(food),
                                     saturation = VALUES(saturation),
                                     potion_effects = VALUES(potion_effects),
                                     revision = VALUES(revision),
                                     server = VALUES(server),
                                     updated_at = VALUES(updated_at)
            """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, data.getUuid().toString());
            ps.setBytes(2, ItemSerializer.serializeArray(data.getInventoryContents()));
            ps.setBytes(3, ItemSerializer.serializeArray(data.getArmorContents()));
            ps.setBytes(4, ItemSerializer.serializeSingle(data.getOffHand()));
            ps.setBytes(5, ItemSerializer.serializeArray(data.getEnderChestContents()));
            ps.setInt(6, data.getXp());
            ps.setInt(7, data.getLevel());
            ps.setDouble(8, data.getHealth());
            ps.setInt(9, data.getFoodLevel());
            ps.setFloat(10, data.getSaturation());
            ps.setBytes(11, ItemSerializer.serializePotionEffects(data.getPotionEffects()));
            ps.setLong(12, data.getRevision());
            ps.setString(13, data.getServer());
            ps.setLong(14, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private void upsertEconomyTx(Connection c, PlayerData data) throws SQLException {
        String sql = """
            INSERT INTO economy (uuid, balance, revision, updated_at)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE balance = VALUES(balance),
                                     revision = VALUES(revision),
                                     updated_at = VALUES(updated_at)
            """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, data.getUuid().toString());
            ps.setDouble(2, data.getBalance());
            ps.setLong(3, data.getRevision());
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public Optional<PlayerData> loadCurrentState(UUID uuid, String username) throws SQLException {
        String sql = """
            SELECT i.inventory_data, i.armor_data, i.offhand_data, i.enderchest_data,
                   i.xp, i.level, i.health, i.food, i.saturation, i.potion_effects,
                   i.revision, i.server, e.balance
            FROM inventories i
            LEFT JOIN economy e ON e.uuid = i.uuid
            WHERE i.uuid = ?
            """;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();

                PlayerData data = new PlayerData(uuid, username);
                data.setInventoryContents(ItemSerializer.deserializeArray(rs.getBytes("inventory_data")));
                data.setArmorContents(ItemSerializer.deserializeArray(rs.getBytes("armor_data")));
                data.setOffHand(ItemSerializer.deserializeSingle(rs.getBytes("offhand_data")));
                data.setEnderChestContents(ItemSerializer.deserializeArray(rs.getBytes("enderchest_data")));
                data.setXp(rs.getInt("xp"));
                data.setLevel(rs.getInt("level"));
                data.setHealth(rs.getDouble("health"));
                data.setFoodLevel(rs.getInt("food"));
                data.setSaturation(rs.getFloat("saturation"));
                data.setPotionEffects(ItemSerializer.deserializePotionEffects(rs.getBytes("potion_effects")));
                data.setRevision(rs.getLong("revision"));
                data.setServer(rs.getString("server"));
                data.setBalance(rs.getDouble("balance"));
                return Optional.of(data);
            }
        }
    }

    // =========================================================
    //  inventory_backups (snapshots)
    // =========================================================

    public long createBackup(PlayerData data, String reason) throws SQLException {
        String insert = """
            INSERT INTO inventory_backups (uuid, server, inventory_data, armor_data, offhand_data,
                                            enderchest_data, xp, level, balance, reason, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, data.getUuid().toString());
            ps.setString(2, data.getServer());
            ps.setBytes(3, ItemSerializer.serializeArray(data.getInventoryContents()));
            ps.setBytes(4, ItemSerializer.serializeArray(data.getArmorContents()));
            ps.setBytes(5, ItemSerializer.serializeSingle(data.getOffHand()));
            ps.setBytes(6, ItemSerializer.serializeArray(data.getEnderChestContents()));
            ps.setInt(7, data.getXp());
            ps.setInt(8, data.getLevel());
            ps.setDouble(9, data.getBalance());
            ps.setString(10, reason);
            ps.setLong(11, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }
    }

    /** Supprime les plus vieux backups au-delà de la limite "keep" configurée. */
    public void rotateBackups(UUID uuid, int keep) throws SQLException {
        String sql = """
            DELETE FROM inventory_backups
            WHERE uuid = ?
            AND id NOT IN (
                SELECT id FROM (
                    SELECT id FROM inventory_backups WHERE uuid = ? ORDER BY created_at DESC LIMIT ?
                ) AS keep_ids
            )
            """;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, uuid.toString());
            ps.setInt(3, keep);
            ps.executeUpdate();
        }
    }

    public List<BackupEntry> listBackups(UUID uuid, int limit) throws SQLException {
        String sql = "SELECT id, server, reason, created_at, balance FROM inventory_backups WHERE uuid = ? ORDER BY created_at DESC LIMIT ?";
        List<BackupEntry> results = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new BackupEntry(
                            rs.getLong("id"), uuid, rs.getString("server"),
                            rs.getString("reason"), rs.getLong("created_at"), rs.getDouble("balance")));
                }
            }
        }
        return results;
    }

    public Optional<PlayerData> loadBackup(UUID uuid, long backupId, String username) throws SQLException {
        String sql = """
            SELECT inventory_data, armor_data, offhand_data, enderchest_data, xp, level, balance, server
            FROM inventory_backups WHERE uuid = ? AND id = ?
            """;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, backupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                PlayerData data = new PlayerData(uuid, username);
                data.setInventoryContents(ItemSerializer.deserializeArray(rs.getBytes("inventory_data")));
                data.setArmorContents(ItemSerializer.deserializeArray(rs.getBytes("armor_data")));
                data.setOffHand(ItemSerializer.deserializeSingle(rs.getBytes("offhand_data")));
                data.setEnderChestContents(ItemSerializer.deserializeArray(rs.getBytes("enderchest_data")));
                data.setXp(rs.getInt("xp"));
                data.setLevel(rs.getInt("level"));
                data.setBalance(rs.getDouble("balance"));
                data.setServer(rs.getString("server"));
                return Optional.of(data);
            }
        }
    }

    /** Récupère l'id du dernier backup (pour /sync rollback = revenir au précédent). */
    public Optional<Long> getPreviousBackupId(UUID uuid, long excludeId) throws SQLException {
        String sql = "SELECT id FROM inventory_backups WHERE uuid = ? AND id < ? ORDER BY created_at DESC LIMIT 1";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, excludeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    public Optional<Long> getLatestBackupId(UUID uuid) throws SQLException {
        String sql = "SELECT id FROM inventory_backups WHERE uuid = ? ORDER BY created_at DESC LIMIT 1";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    // =========================================================
    //  transactions (historique économie)
    // =========================================================

    public void logTransaction(UUID uuid, String type, double amount, double before, double after,
                                String server, String reason) throws SQLException {
        String sql = """
            INSERT INTO transactions (uuid, type, amount, balance_before, balance_after, server, reason, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, type);
            ps.setDouble(3, amount);
            ps.setDouble(4, before);
            ps.setDouble(5, after);
            ps.setString(6, server);
            ps.setString(7, reason);
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public List<String> getEconomyHistory(UUID uuid, int limit) throws SQLException {
        String sql = "SELECT type, amount, balance_before, balance_after, server, reason, timestamp FROM transactions WHERE uuid = ? ORDER BY timestamp DESC LIMIT ?";
        List<String> lines = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(String.format("[%s] %s %.2f (%.2f -> %.2f) sur %s - %s",
                            new java.util.Date(rs.getLong("timestamp")),
                            rs.getString("type"), rs.getDouble("amount"),
                            rs.getDouble("balance_before"), rs.getDouble("balance_after"),
                            rs.getString("server"), rs.getString("reason")));
                }
            }
        }
        return lines;
    }

    // =========================================================
    //  player_lock (anti double-connexion)
    // =========================================================

    /** Tente de poser un verrou. Retourne true si acquis, false si un autre serveur détient déjà un verrou actif. */
    public boolean tryAcquireLock(UUID uuid, String serverId, String sessionId, long timeoutMs) throws SQLException {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                String select = "SELECT server_id, last_heartbeat FROM player_lock WHERE uuid = ? FOR UPDATE";
                try (PreparedStatement ps = c.prepareStatement(select)) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long lastHeartbeat = rs.getLong("last_heartbeat");
                            boolean expired = (System.currentTimeMillis() - lastHeartbeat) > timeoutMs;
                            if (!expired) {
                                c.rollback();
                                return false; // verrou actif détenu ailleurs
                            }
                        }
                    }
                }
                String upsert = """
                    INSERT INTO player_lock (uuid, server_id, session_id, last_heartbeat, locked_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE server_id = VALUES(server_id),
                                             session_id = VALUES(session_id),
                                             last_heartbeat = VALUES(last_heartbeat),
                                             locked_at = VALUES(locked_at)
                    """;
                try (PreparedStatement ps = c.prepareStatement(upsert)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, serverId);
                    ps.setString(3, sessionId);
                    long now = System.currentTimeMillis();
                    ps.setLong(4, now);
                    ps.setLong(5, now);
                    ps.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public void heartbeat(UUID uuid, String sessionId) throws SQLException {
        String sql = "UPDATE player_lock SET last_heartbeat = ? WHERE uuid = ? AND session_id = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, uuid.toString());
            ps.setString(3, sessionId);
            ps.executeUpdate();
        }
    }

    public void releaseLock(UUID uuid, String sessionId) throws SQLException {
        String sql = "DELETE FROM player_lock WHERE uuid = ? AND session_id = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, sessionId);
            ps.executeUpdate();
        }
    }
}
