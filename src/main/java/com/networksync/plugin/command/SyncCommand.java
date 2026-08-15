package com.networksync.plugin.command;

import com.networksync.plugin.NetworkSyncPlugin;
import com.networksync.plugin.model.BackupEntry;
import com.networksync.plugin.model.PlayerData;
import com.networksync.plugin.util.ItemSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * /sync backup <joueur>
 * /sync backups <joueur>
 * /sync restore <joueur> <backup-id>
 * /sync rollback <joueur>
 * /sync economy-history <joueur>
 * /sync status <joueur>
 */
public class SyncCommand implements CommandExecutor {

    private final NetworkSyncPlugin plugin;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

    public SyncCommand(NetworkSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7Usage: §f/sync <backup|backups|restore|rollback|economy-history|status> <joueur> [id]");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "backup" -> handleBackup(sender, args);
            case "backups" -> handleListBackups(sender, args);
            case "restore" -> handleRestore(sender, args);
            case "rollback" -> handleRollback(sender, args);
            case "economy-history" -> handleEconomyHistory(sender, args);
            case "status" -> handleStatus(sender, args);
            default -> sender.sendMessage("§cSous-commande inconnue.");
        }
        return true;
    }

    private OfflinePlayer resolveTarget(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            sender.sendMessage("§cIndique un joueur.");
            return null;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[index]);
        if (target.getUniqueId() == null) {
            sender.sendMessage("§cJoueur introuvable.");
            return null;
        }
        return target;
    }

    // ---------------------------------------------------------
    private void handleBackup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sync.backup")) { sender.sendMessage("§cPermission refusée."); return; }
        OfflinePlayer target = resolveTarget(sender, args, 1);
        if (target == null) return;

        PlayerData data = resolveDataForBackup(target);
        if (data == null) {
            sender.sendMessage("§cImpossible de récupérer les données de ce joueur.");
            return;
        }
        plugin.getBackupManager().createBackupAsync(data, "MANUAL_ADMIN", () ->
                sender.sendMessage("§aBackup créé pour " + target.getName() + "."));
    }

    private PlayerData resolveDataForBackup(OfflinePlayer target) {
        Player online = target.getPlayer();
        if (online != null) {
            PlayerData cached = plugin.getSyncManager().getCached(online.getUniqueId());
            if (cached != null) return cached;
        }
        // joueur hors-ligne : on charge son état courant en DB de façon synchrone (thread async attendu)
        try {
            Optional<PlayerData> opt = plugin.getDao().loadCurrentState(target.getUniqueId(), target.getName());
            return opt.orElse(null);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Erreur de chargement pour backup manuel", e);
            return null;
        }
    }

    // ---------------------------------------------------------
    private void handleListBackups(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sync.view")) { sender.sendMessage("§cPermission refusée."); return; }
        OfflinePlayer target = resolveTarget(sender, args, 1);
        if (target == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<BackupEntry> backups = plugin.getBackupManager().listBackups(target.getUniqueId(), 20);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (backups.isEmpty()) {
                        sender.sendMessage("§7Aucun backup trouvé pour " + target.getName() + ".");
                        return;
                    }
                    sender.sendMessage("§6Backups de " + target.getName() + " :");
                    for (BackupEntry b : backups) {
                        sender.sendMessage(String.format("§7#%d §f- %s §7(%s) §f- %.2f$",
                                b.getId(), dateFormat.format(new java.util.Date(b.getCreatedAt())),
                                b.getReason(), b.getBalance()));
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur de listing des backups", e);
                sender.sendMessage("§cErreur lors de la récupération des backups.");
            }
        });
    }

    // ---------------------------------------------------------
    private void handleRestore(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sync.restore")) { sender.sendMessage("§cPermission refusée."); return; }
        OfflinePlayer target = resolveTarget(sender, args, 1);
        if (target == null) return;
        if (args.length < 3) { sender.sendMessage("§cUsage: /sync restore <joueur> <backup-id>"); return; }

        long backupId;
        try {
            backupId = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cID de backup invalide.");
            return;
        }

        doRestore(sender, target, backupId);
    }

    private void handleRollback(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sync.restore")) { sender.sendMessage("§cPermission refusée."); return; }
        OfflinePlayer target = resolveTarget(sender, args, 1);
        if (target == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<Long> latest = plugin.getBackupManager().getLatestBackupId(target.getUniqueId());
                if (latest.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§7Aucun backup disponible."));
                    return;
                }
                Optional<Long> previous = plugin.getBackupManager().getPreviousBackupId(target.getUniqueId(), latest.get());
                long targetId = previous.orElse(latest.get());
                Bukkit.getScheduler().runTask(plugin, () -> doRestore(sender, target, targetId));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur rollback", e);
                sender.sendMessage("§cErreur lors du rollback.");
            }
        });
    }

    private void doRestore(CommandSender sender, OfflinePlayer target, long backupId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Backup de sécurité avant restauration (toujours, sauf désactivé en config)
                if (plugin.getBackupManager().isBeforeRestoreEnabled()) {
                    PlayerData current = resolveDataForBackup(target);
                    if (current != null) {
                        plugin.getBackupManager().createBackup(current, "BEFORE_RESTORE");
                    }
                }

                Optional<PlayerData> restoredOpt = plugin.getBackupManager()
                        .loadBackup(target.getUniqueId(), backupId, target.getName());
                if (restoredOpt.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§cBackup #" + backupId + " introuvable."));
                    return;
                }

                PlayerData restored = restoredOpt.get();
                restored.setServer(plugin.getSyncManager().getServerId());
                restored.setRevision(plugin.getDao().getKnownRevision(target.getUniqueId()) + 1);

                boolean accepted = plugin.getDao().saveCurrentState(restored);
                if (!accepted) {
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§cRestauration refusée (conflit de revision)."));
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("§aBackup #" + backupId + " restauré pour " + target.getName() + ".");
                    Player online = target.getPlayer();
                    if (online != null) {
                        plugin.getSyncManager().cachePlayer(restored);
                        ItemSerializer.applyToInventory(online.getInventory(), restored.getInventoryContents(),
                                restored.getArmorContents(), restored.getOffHand());
                        online.getEnderChest().setContents(restored.getEnderChestContents());
                        online.setLevel(restored.getLevel());
                        online.setTotalExperience(restored.getXp());
                        if (plugin.getEconomyManager().isEnabled()) {
                            plugin.getEconomyManager().applySyncedBalance(online, restored.getBalance(),
                                    plugin.getSyncManager().getServerId(), "RESTORE_BACKUP_" + backupId);
                        }
                        online.sendMessage("§eTon inventaire a été restauré par un administrateur (backup #" + backupId + ").");
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur de restauration", e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§cErreur lors de la restauration."));
            }
        });
    }

    // ---------------------------------------------------------
    private void handleEconomyHistory(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sync.view")) { sender.sendMessage("§cPermission refusée."); return; }
        OfflinePlayer target = resolveTarget(sender, args, 1);
        if (target == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<String> history = plugin.getDao().getEconomyHistory(target.getUniqueId(), 15);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (history.isEmpty()) {
                        sender.sendMessage("§7Aucune transaction trouvée pour " + target.getName() + ".");
                        return;
                    }
                    sender.sendMessage("§6Historique économie de " + target.getName() + " :");
                    history.forEach(line -> sender.sendMessage("§7" + line));
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur historique économie", e);
                sender.sendMessage("§cErreur lors de la récupération de l'historique.");
            }
        });
    }

    // ---------------------------------------------------------
    private void handleStatus(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§6NetworkSync §7- serveur: §f" + plugin.getSyncManager().getServerId()
                    + " §7- joueurs en cache: §f" + Bukkit.getOnlinePlayers().size());
            return;
        }
        OfflinePlayer target = resolveTarget(sender, args, 1);
        if (target == null) return;
        UUID uuid = target.getUniqueId();
        boolean locked = plugin.getSessionManager().hasLock(uuid);
        PlayerData cached = plugin.getSyncManager().getCached(uuid);
        sender.sendMessage("§6Status de " + target.getName() + " :");
        sender.sendMessage("§7Verrou détenu par ce serveur: §f" + locked);
        sender.sendMessage("§7En cache mémoire: §f" + (cached != null));
        if (cached != null) {
            sender.sendMessage("§7Revision: §f" + cached.getRevision() + " §7Dirty: §f" + cached.isDirty());
        }
    }
}
