package com.networksync.plugin;

import com.networksync.plugin.backup.BackupManager;
import com.networksync.plugin.command.SyncCommand;
import com.networksync.plugin.database.DatabaseManager;
import com.networksync.plugin.database.PlayerDataDAO;
import com.networksync.plugin.economy.EconomyManager;
import com.networksync.plugin.listener.InventoryListener;
import com.networksync.plugin.listener.PlayerConnectionListener;
import com.networksync.plugin.listener.ServerSwitchListener;
import com.networksync.plugin.session.SessionManager;
import com.networksync.plugin.sync.SyncManager;
import org.bukkit.plugin.java.JavaPlugin;

public class NetworkSyncPlugin extends JavaPlugin {

    private DatabaseManager databaseManager;
    private PlayerDataDAO dao;
    private SessionManager sessionManager;
    private SyncManager syncManager;
    private BackupManager backupManager;
    private EconomyManager economyManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getLogger().info("Connexion à MySQL/MariaDB...");
        this.databaseManager = new DatabaseManager(this);
        try {
            databaseManager.connect();
        } catch (Exception e) {
            getLogger().severe("Impossible de se connecter à la base de données. Le plugin est désactivé.");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.dao = new PlayerDataDAO(databaseManager);

        this.sessionManager = new SessionManager(this);
        this.syncManager = new SyncManager(this);
        this.backupManager = new BackupManager(this);
        this.economyManager = new EconomyManager(this);

        registerListeners();
        registerCommands();

        getLogger().info("NetworkSync activé sur le serveur '" + syncManager.getServerId() + "'.");
    }

    @Override
    public void onDisable() {
        // Sauvegarde immédiate de tous les joueurs encore en cache (arrêt/reload du serveur)
        if (syncManager != null) {
            for (var player : getServer().getOnlinePlayers()) {
                var data = syncManager.getCached(player.getUniqueId());
                if (data != null) {
                    PlayerConnectionListener.captureFromPlayer(player, data);
                    try {
                        dao.saveCurrentState(data);
                        backupManager.createBackup(data, "SHUTDOWN");
                    } catch (Exception e) {
                        getLogger().severe("Erreur de sauvegarde à l'arrêt pour " + player.getName() + " : " + e.getMessage());
                    }
                }
                sessionManager.releaseLock(player.getUniqueId());
            }
        }

        if (syncManager != null) syncManager.stop();
        if (backupManager != null) backupManager.stop();
        if (databaseManager != null) databaseManager.close();

        getLogger().info("NetworkSync désactivé proprement.");
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new InventoryListener(this), this);
        getServer().getPluginManager().registerEvents(new ServerSwitchListener(this), this);
    }

    private void registerCommands() {
        SyncCommand syncCommand = new SyncCommand(this);
        var command = getCommand("sync");
        if (command != null) {
            command.setExecutor(syncCommand);
        } else {
            getLogger().warning("Commande 'sync' introuvable dans plugin.yml !");
        }
    }

    // --- Accesseurs pour les autres classes ---

    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public PlayerDataDAO getDao() { return dao; }
    public SessionManager getSessionManager() { return sessionManager; }
    public SyncManager getSyncManager() { return syncManager; }
    public BackupManager getBackupManager() { return backupManager; }
    public EconomyManager getEconomyManager() { return economyManager; }
}
