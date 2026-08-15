package com.networksync.plugin.backup;

import com.networksync.plugin.NetworkSyncPlugin;
import com.networksync.plugin.database.PlayerDataDAO;
import com.networksync.plugin.model.BackupEntry;
import com.networksync.plugin.model.PlayerData;
import com.networksync.plugin.sync.SyncManager;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Backups automatiques toutes les `interval-seconds` (30s par défaut), mais
 * UNIQUEMENT pour les joueurs marqués "dirty" depuis le dernier passage
 * (voir SyncManager#consumeDirtySinceBackup). Un joueur inchangé ne génère
 * aucune écriture.
 *
 * Ajoute aussi les backups "forcés" : changement de serveur, déconnexion,
 * avant restauration, avant action admin.
 */
public class BackupManager {

    private final NetworkSyncPlugin plugin;
    private final PlayerDataDAO dao;
    private final SyncManager syncManager;
    private final int keep;
    private int taskId = -1;

    public BackupManager(NetworkSyncPlugin plugin) {
        this.plugin = plugin;
        this.dao = plugin.getDao();
        this.syncManager = plugin.getSyncManager();
        this.keep = plugin.getConfig().getInt("backups.keep", 20);

        if (plugin.getConfig().getBoolean("backups.enabled", true)) {
            startScheduler();
        }
    }

    private void startScheduler() {
        long intervalTicks = plugin.getConfig().getLong("backups.interval-seconds", 30) * 20L;
        taskId = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::runPeriodicBackup, intervalTicks, intervalTicks).getTaskId();
    }

    public void stop() {
        if (taskId != -1) plugin.getServer().getScheduler().cancelTask(taskId);
    }

    /** Exécuté toutes les 30s : ne sauvegarde que les joueurs dirty depuis le dernier passage. */
    private void runPeriodicBackup() {
        boolean onlyIfChanged = plugin.getConfig().getBoolean("backups.only-if-changed", true);

        Set<UUID> candidates;
        if (onlyIfChanged) {
            candidates = syncManager.consumeDirtySinceBackup();
        } else {
            candidates = plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getUniqueId).collect(java.util.stream.Collectors.toSet());
        }

        if (candidates.isEmpty()) return;

        for (UUID uuid : candidates) {
            PlayerData data = syncManager.getCached(uuid);
            if (data == null) continue;
            createBackup(data, "PERIODIC");
        }
    }

    /** Crée un snapshot pour ce joueur avec la raison donnée, puis applique la rotation. */
    public void createBackup(PlayerData data, String reason) {
        try {
            dao.createBackup(data, reason);
            dao.rotateBackups(data.getUuid(), keep);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Échec de création de backup (" + reason + ") pour "
                    + data.getUsername(), e);
        }
    }

    /** Version async utilisable depuis le thread principal (ex: commande admin). */
    public void createBackupAsync(PlayerData data, String reason, Runnable onDone) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            createBackup(data, reason);
            if (onDone != null) {
                plugin.getServer().getScheduler().runTask(plugin, onDone);
            }
        });
    }

    public List<BackupEntry> listBackups(UUID uuid, int limit) throws Exception {
        return dao.listBackups(uuid, limit);
    }

    public Optional<PlayerData> loadBackup(UUID uuid, long backupId, String username) throws Exception {
        return dao.loadBackup(uuid, backupId, username);
    }

    public Optional<Long> getPreviousBackupId(UUID uuid, long excludeId) throws Exception {
        return dao.getPreviousBackupId(uuid, excludeId);
    }

    public Optional<Long> getLatestBackupId(UUID uuid) throws Exception {
        return dao.getLatestBackupId(uuid);
    }

    public boolean isBeforeRestoreEnabled() {
        return plugin.getConfig().getBoolean("backups.before-restore", true);
    }

    public boolean isBeforeAdminActionEnabled() {
        return plugin.getConfig().getBoolean("backups.before-admin-action", true);
    }
}
