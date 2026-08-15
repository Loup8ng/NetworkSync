package com.networksync.plugin.economy;

import com.networksync.plugin.NetworkSyncPlugin;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Ne modifie jamais directement la base d'EssentialsX : passe systématiquement
 * par Vault (Economy Provider), qu'EssentialsX enregistre lui-même.
 * NetworkSync ne fait qu'utiliser get/withdraw/deposit et journaliser en DB
 * (table transactions) pour garder un historique indépendant.
 */
public class EconomyManager {

    private final NetworkSyncPlugin plugin;
    private Economy economy;
    private boolean enabled;

    public EconomyManager(NetworkSyncPlugin plugin) {
        this.plugin = plugin;
        setup();
    }

    private void setup() {
        if (!plugin.getConfig().getBoolean("economy.enabled", true)) {
            enabled = false;
            return;
        }
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault introuvable : la synchronisation d'économie est désactivée.");
            enabled = false;
            return;
        }
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            plugin.getLogger().warning("Aucun fournisseur d'économie Vault trouvé (EssentialsX manquant ?).");
            enabled = false;
            return;
        }
        this.economy = rsp.getProvider();
        this.enabled = true;
        plugin.getLogger().info("Économie liée via Vault -> " + economy.getName());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getBalance(OfflinePlayer player) {
        if (!enabled) return 0;
        return economy.getBalance(player);
    }

    /**
     * Force le solde local (Vault/EssentialsX) à correspondre au solde synchronisé,
     * en journalisant la différence dans la table transactions.
     */
    public void applySyncedBalance(OfflinePlayer player, double targetBalance, String server, String reason) {
        if (!enabled) return;
        double current = economy.getBalance(player);
        double delta = targetBalance - current;

        if (Math.abs(delta) < 0.0001) return; // déjà cohérent

        if (delta > 0) {
            economy.depositPlayer(player, delta);
        } else {
            economy.withdrawPlayer(player, -delta);
        }

        double after = economy.getBalance(player);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getDao().logTransaction(player.getUniqueId(), "SYNC_APPLY", delta, current, after, server, reason);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Échec de journalisation de transaction", e);
            }
        });
    }

    /** Enregistre une transaction arbitraire (achat, vente, admin, etc.) initiée en jeu. */
    public void logManualTransaction(UUID uuid, String type, double amount, double before, double after,
                                      String server, String reason) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getDao().logTransaction(uuid, type, amount, before, after, server, reason);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Échec de journalisation de transaction", e);
            }
        });
    }
}
