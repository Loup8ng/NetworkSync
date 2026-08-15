package com.networksync.plugin.session;

import com.networksync.plugin.NetworkSyncPlugin;
import com.networksync.plugin.database.PlayerDataDAO;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Empêche un joueur d'être actif simultanément sur deux serveurs.
 *
 * Flux : déconnexion serveur A -> verrouillage -> sauvegarde DB -> serveur B
 * demande les données -> chargement -> déverrouillage.
 * Si le serveur A crash sans libérer le verrou, le serveur B peut reprendre
 * la main après expiration du heartbeat (lock-timeout-seconds).
 */
public class SessionManager {

    private final NetworkSyncPlugin plugin;
    private final PlayerDataDAO dao;
    private final Map<UUID, String> activeSessions = new ConcurrentHashMap<>();
    private final String serverId;
    private final long lockTimeoutMs;

    public SessionManager(NetworkSyncPlugin plugin) {
        this.plugin = plugin;
        this.dao = plugin.getDao();
        this.serverId = plugin.getConfig().getString("server-id", "SMP-1");
        this.lockTimeoutMs = plugin.getConfig().getLong("session.lock-timeout-seconds", 15) * 1000L;

        // Heartbeat périodique pour tous les joueurs actuellement verrouillés par CE serveur
        long interval = plugin.getConfig().getLong("session.heartbeat-interval-seconds", 5) * 20L;
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::sendHeartbeats, interval, interval);
    }

    private void sendHeartbeats() {
        for (Map.Entry<UUID, String> entry : activeSessions.entrySet()) {
            try {
                dao.heartbeat(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                plugin.getLogger().warning("Échec heartbeat pour " + entry.getKey() + " : " + e.getMessage());
            }
        }
    }

    /** Tente d'acquérir le verrou pour ce joueur sur ce serveur. À appeler de façon asynchrone. */
    public CompletableFuture<Boolean> acquireLock(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sessionId = UUID.randomUUID().toString();
            try {
                boolean acquired = dao.tryAcquireLock(uuid, serverId, sessionId, lockTimeoutMs);
                if (acquired) {
                    activeSessions.put(uuid, sessionId);
                }
                return acquired;
            } catch (Exception e) {
                plugin.getLogger().severe("Erreur d'acquisition de verrou pour " + uuid + " : " + e.getMessage());
                return false;
            }
        });
    }

    public void releaseLock(UUID uuid) {
        String sessionId = activeSessions.remove(uuid);
        if (sessionId == null) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                dao.releaseLock(uuid, sessionId);
            } catch (Exception e) {
                plugin.getLogger().warning("Échec de libération du verrou pour " + uuid + " : " + e.getMessage());
            }
        });
    }

    public boolean hasLock(UUID uuid) {
        return activeSessions.containsKey(uuid);
    }
}
