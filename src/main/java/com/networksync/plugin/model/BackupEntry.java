package com.networksync.plugin.model;

import java.util.UUID;

/** Métadonnées d'un backup/snapshot stocké dans inventory_backups. */
public class BackupEntry {

    private final long id;
    private final UUID uuid;
    private final String server;
    private final String reason;
    private final long createdAt;
    private final double balance;

    public BackupEntry(long id, UUID uuid, String server, String reason, long createdAt, double balance) {
        this.id = id;
        this.uuid = uuid;
        this.server = server;
        this.reason = reason;
        this.createdAt = createdAt;
        this.balance = balance;
    }

    public long getId() { return id; }
    public UUID getUuid() { return uuid; }
    public String getServer() { return server; }
    public String getReason() { return reason; }
    public long getCreatedAt() { return createdAt; }
    public double getBalance() { return balance; }
}
