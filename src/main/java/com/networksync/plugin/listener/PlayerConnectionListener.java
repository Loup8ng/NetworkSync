package com.networksync.plugin.listener;

import com.networksync.plugin.NetworkSyncPlugin;
import com.networksync.plugin.model.PlayerData;
import com.networksync.plugin.util.ItemSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Flux de connexion :
 *   AsyncPreLogin -> tentative de verrouillage (refuse la connexion si le joueur
 *   est considéré actif sur un autre serveur et que le verrou n'a pas expiré)
 *   -> PlayerJoin -> chargement depuis la DB -> application in-game
 *
 * Flux de déconnexion :
 *   PlayerQuit -> sauvegarde immédiate (bypass micro-batching) -> backup "on-quit"
 *   -> libération du verrou
 */
public class PlayerConnectionListener implements Listener {

    private final NetworkSyncPlugin plugin;

    public PlayerConnectionListener(NetworkSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();
        boolean acquired = plugin.getSessionManager().acquireLock(uuid).join();
        if (!acquired) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "§cVotre session est encore active sur un autre serveur du réseau.\n" +
                    "§7Réessayez dans quelques secondes.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<PlayerData> loaded = plugin.getDao().loadCurrentState(uuid, player.getName());
                PlayerData data = loaded.orElseGet(() -> {
                    PlayerData fresh = new PlayerData(uuid, player.getName());
                    fresh.setServer(plugin.getSyncManager().getServerId());
                    return fresh;
                });
                data.setServer(plugin.getSyncManager().getServerId());
                plugin.getSyncManager().cachePlayer(data);

                plugin.getServer().getScheduler().runTask(plugin, () -> applyToPlayer(player, data));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur de chargement des données pour " + player.getName(), e);
            }
        });
    }

    private void applyToPlayer(Player player, PlayerData data) {
        if (!player.isOnline()) return;

        ItemSerializer.applyToInventory(player.getInventory(), data.getInventoryContents(),
                data.getArmorContents(), data.getOffHand());
        player.getEnderChest().setContents(data.getEnderChestContents());
        player.setExp(0);
        player.setLevel(data.getLevel());
        player.setTotalExperience(data.getXp());

        if (data.getHealth() > 0) {
            double max = Double.valueOf(player.getMaxHealth()).longValue();
            player.setHealth(Math.min(data.getHealth(), max));
        }
        player.setFoodLevel(data.getFoodLevel());
        player.setSaturation(data.getSaturation());

        player.getActivePotionEffects().forEach(eff -> player.removePotionEffect(eff.getType()));
        data.getPotionEffects().forEach(player::addPotionEffect);

        if (plugin.getConfig().getBoolean("economy.verify-balance-on-load", true) && plugin.getEconomyManager().isEnabled()) {
            plugin.getEconomyManager().applySyncedBalance(player, data.getBalance(),
                    plugin.getSyncManager().getServerId(), "LOGIN_SYNC");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        PlayerData data = plugin.getSyncManager().getCached(uuid);
        if (data != null) {
            captureFromPlayer(player, data);

            boolean saveOnQuit = plugin.getConfig().getBoolean("sync.save-on-quit", true);
            if (saveOnQuit) {
                plugin.getSyncManager().flushImmediately(uuid).thenAccept(accepted -> {
                    if (accepted && plugin.getConfig().getBoolean("backups.on-quit", true)) {
                        plugin.getBackupManager().createBackup(data, "QUIT");
                    }
                    plugin.getSessionManager().releaseLock(uuid);
                });
            } else {
                plugin.getSessionManager().releaseLock(uuid);
            }
        } else {
            plugin.getSessionManager().releaseLock(uuid);
        }

        plugin.getSyncManager().uncache(uuid);
    }

    /** Recopie l'état vivant du joueur Bukkit dans le PlayerData avant sauvegarde finale. */
    public static void captureFromPlayer(Player player, PlayerData data) {
        data.setInventoryContents(player.getInventory().getContents());
        data.setArmorContents(player.getInventory().getArmorContents());
        data.setOffHand(player.getInventory().getItemInOffHand());
        data.setEnderChestContents(player.getEnderChest().getContents());
        data.setXp(player.getTotalExperience());
        data.setLevel(player.getLevel());
        data.setHealth(player.getHealth());
        data.setFoodLevel(player.getFoodLevel());
        data.setSaturation(player.getSaturation());
        data.setPotionEffects(java.util.List.copyOf(player.getActivePotionEffects().stream()
                .map(e -> (org.bukkit.potion.PotionEffect) e).toList()));
        data.incrementRevision();
    }
}
