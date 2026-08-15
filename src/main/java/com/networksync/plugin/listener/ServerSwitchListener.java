package com.networksync.plugin.listener;

import com.networksync.plugin.NetworkSyncPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Force une sauvegarde immédiate (bypass micro-batching) dès qu'un joueur
 * est sur le point de changer de serveur, afin qu'il ne parte jamais avec
 * un inventaire "vieux" de quelques secondes.
 *
 * En environnement Velocity, on écoute le canal "bungeecord:main" / "networksync:main"
 * pour être notifié avant le transfert effectif ; en environnement mono-serveur
 * standalone, ce listener ne se déclenche simplement jamais et n'a aucun effet.
 */
public class ServerSwitchListener implements Listener, PluginMessageListener {

    private final NetworkSyncPlugin plugin;

    public ServerSwitchListener(NetworkSyncPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "networksync:main");
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "networksync:main", this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("networksync:main")) return;
        forceSaveBeforeSwitch(player);
    }

    /** Peut aussi être déclenché manuellement (ex: commande /server côté proxy interceptée). */
    public void forceSaveBeforeSwitch(Player player) {
        if (!plugin.getConfig().getBoolean("sync.save-on-server-switch", true)) return;

        UUID uuid = player.getUniqueId();
        var data = plugin.getSyncManager().getCached(uuid);
        if (data == null) return;

        PlayerConnectionListener.captureFromPlayer(player, data);

        plugin.getSyncManager().flushImmediately(uuid).thenAccept(accepted -> {
            if (accepted && plugin.getConfig().getBoolean("backups.on-server-switch", true)) {
                plugin.getBackupManager().createBackup(data, "SERVER_SWITCH");
            }
        });
    }

    /** Envoie un message au proxy Velocity, utile si tu ajoutes une commande /server custom. */
    public void notifyProxyReadyToSwitch(Player player, String targetServer) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeUTF("SwitchReady");
            out.writeUTF(targetServer);
            player.sendPluginMessage(plugin, "networksync:main", baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible d'envoyer le message proxy : " + e.getMessage());
        }
    }
}
