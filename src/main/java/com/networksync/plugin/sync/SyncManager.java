package com.networksync.plugin.sync;

import com.networksync.plugin.NetworkSyncPlugin;
import com.networksync.plugin.database.PlayerDataDAO;
import com.networksync.plugin.model.PlayerData;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.logging.Level;

/**
 * Gère la synchronisation temps réel des joueurs connectés.
 *
 * Principe : chaque action modifie immédiatement l'état en mémoire (PlayerData)
 * et marque le joueur "dirty". Une file de flush regroupe les écritures DB
 * survenues dans une fenêtre de `flush-delay-ms` (micro-batching), pour éviter
 * une requête SQL par clic de souris.
 *
 * Le changement de serveur et la déconnexion déclenchent toujours une écriture
 * immédiate (bypass du micro-batching) afin de ne jamais perdre les dernières actions.
 */
public class SyncManager {

    private final NetworkSyncPlugin plugin;
    private final PlayerDataDAO dao;
    private final String serverId;

    /** État en mémoire de chaque joueur actuellement connecté. */
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    /** Joueurs dont l'état mémoire a changé depuis le dernier flush DB. */
    private final KeySetView<UUID, Boolean> pendingFlush = ConcurrentHashMap.newKeySet();

    /** Joueurs "dirty" depuis le dernier backup périodique (consommé par le BackupManager). */
    private final Set<UUID> dirtySinceBackup = ConcurrentHashMap.newKeySet();

    private int flushTaskId = -1;

    public SyncManager(NetworkSyncPlugin plugin) {
        this.plugin = plugin;
        this.dao = plugin.getDao();
        this.serverId = plugin.getConfig().getString("server-id", "SMP-1");
        startFlushTask();
    }

    private void startFlushTask() {
        long delayMs = plugin.getConfig().getLong("sync.flush-delay-ms", 50);
        long ticks = Math.max(1L, delayMs / 50L); // 1 tick = 50ms
        flushTaskId = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::flushPending, ticks, ticks).getTaskId();
    }

    public void stop() {
        if (flushTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(flushTaskId);
        }
    }

    // ---------------------------------------------------------
    //  Cache mémoire
    // ---------------------------------------------------------

    public void cachePlayer(PlayerData data) {
        cache.put(data.getUuid(), data);
    }

    public PlayerData getCached(UUID uuid) {
        return cache.get(uuid);
    }

    public void uncache(UUID uuid) {
        cache.remove(uuid);
    }

    /** À appeler après toute modification d'état (inventaire, xp, etc.) pour planifier l'écriture DB. */
    public void markDirty(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data == null) return;
        data.markDirty();
        data.incrementRevision();
        pendingFlush.add(uuid);
        dirtySinceBackup.add(uuid);
    }

    // ---------------------------------------------------------
    //  Micro-batching : flush périodique (toutes les flush-delay-ms)
    // ---------------------------------------------------------

    private void flushPending() {
        if (pendingFlush.isEmpty()) return;
        for (UUID uuid : Set.copyOf(pendingFlush)) {
            pendingFlush.remove(uuid);
            PlayerData data = cache.get(uuid);
            if (data == null) continue;
            persist(data);
        }
    }

    private void persist(PlayerData data) {
        try {
            boolean accepted = dao.saveCurrentState(data);
            if (!accepted) {
                plugin.getLogger().warning("Écriture refusée (revision périmée) pour " + data.getUsername()
                        + " - un autre serveur a une version plus récente.");
                return;
            }
            data.clearDirty();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Erreur de synchronisation pour " + data.getUsername(), e);
        }
    }

    /** Sauvegarde immédiate et bloquante (retourne un Future) : utilisée pour switch de serveur / déconnexion. */
    public CompletableFuture<Boolean> flushImmediately(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data == null) return CompletableFuture.completedFuture(true);
        pendingFlush.remove(uuid);
        return CompletableFuture.supplyAsync(() -> {
            try {
                boolean accepted = dao.saveCurrentState(data);
                if (accepted) data.clearDirty();
                return accepted;
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur de sauvegarde immédiate pour " + data.getUsername(), e);
                return false;
            }
        });
    }

    // ---------------------------------------------------------
    //  Consommé par le BackupManager
    // ---------------------------------------------------------

    public Set<UUID> consumeDirtySinceBackup() {
        Set<UUID> copy = Set.copyOf(dirtySinceBackup);
        dirtySinceBackup.removeAll(copy);
        return copy;
    }

    public String getServerId() {
        return serverId;
    }
}
