package com.networksync.plugin.listener;

import com.networksync.plugin.NetworkSyncPlugin;
import com.networksync.plugin.model.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;

/**
 * Marque le joueur "dirty" dès qu'une action modifie son inventaire, son
 * équipement, son XP ou son état. Le SyncManager se charge ensuite de
 * regrouper (micro-batching) et d'écrire en base rapidement.
 *
 * On capture systématiquement l'état complet du joueur au moment du dirty
 * plutôt que de calculer un diff par slot : c'est plus simple et largement
 * assez rapide vu le micro-batching (50ms) déjà en place.
 */
public class InventoryListener implements Listener {

    private final NetworkSyncPlugin plugin;

    public InventoryListener(NetworkSyncPlugin plugin) {
        this.plugin = plugin;
    }

    private void touch(Player player) {
        PlayerData data = plugin.getSyncManager().getCached(player.getUniqueId());
        if (data == null) return;
        PlayerConnectionListener.captureFromPlayer(player, data);
        plugin.getSyncManager().markDirty(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        touch(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        touch(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        touch(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        touch(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onXpChange(PlayerExpChangeEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevelChange(PlayerLevelChangeEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        touch(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        touch(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDamage(PlayerItemDamageEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        touch(event.getPlayer());
    }
}
