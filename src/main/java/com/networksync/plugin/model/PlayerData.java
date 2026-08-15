package com.networksync.plugin.model;

import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.List;
import java.util.UUID;

/**
 * Représente l'état complet et synchronisable d'un joueur :
 * inventaire, armure, main secondaire, enderchest, XP, niveau,
 * vie, faim, effets, solde, et métadonnées de synchronisation (revision, serveur).
 */
public class PlayerData {

    private final UUID uuid;
    private String username;

    private ItemStack[] inventoryContents = new ItemStack[41];
    private ItemStack[] armorContents = new ItemStack[4];
    private ItemStack offHand;
    private ItemStack[] enderChestContents = new ItemStack[27];

    private int xp = 0;
    private int level = 0;
    private double health = 20.0;
    private int foodLevel = 20;
    private float saturation = 5.0f;
    private List<PotionEffect> potionEffects = List.of();

    private double balance = 0.0;

    private long revision = 0;
    private String server = "";
    private long updatedAt = 0;

    /** Marqueurs de "slot sale" pour la synchro incrémentale temps réel. */
    private boolean dirty = false;

    public PlayerData(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username;
    }

    // --- Getters / setters ---

    public UUID getUuid() { return uuid; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public ItemStack[] getInventoryContents() { return inventoryContents; }
    public void setInventoryContents(ItemStack[] inventoryContents) { this.inventoryContents = inventoryContents; }

    public ItemStack[] getArmorContents() { return armorContents; }
    public void setArmorContents(ItemStack[] armorContents) { this.armorContents = armorContents; }

    public ItemStack getOffHand() { return offHand; }
    public void setOffHand(ItemStack offHand) { this.offHand = offHand; }

    public ItemStack[] getEnderChestContents() { return enderChestContents; }
    public void setEnderChestContents(ItemStack[] enderChestContents) { this.enderChestContents = enderChestContents; }

    public int getXp() { return xp; }
    public void setXp(int xp) { this.xp = xp; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public double getHealth() { return health; }
    public void setHealth(double health) { this.health = health; }

    public int getFoodLevel() { return foodLevel; }
    public void setFoodLevel(int foodLevel) { this.foodLevel = foodLevel; }

    public float getSaturation() { return saturation; }
    public void setSaturation(float saturation) { this.saturation = saturation; }

    public List<PotionEffect> getPotionEffects() { return potionEffects; }
    public void setPotionEffects(List<PotionEffect> potionEffects) { this.potionEffects = potionEffects; }

    public double getBalance() { return balance; }
    public void setBalance(double balance) { this.balance = balance; }

    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public long incrementRevision() { return ++revision; }

    public String getServer() { return server; }
    public void setServer(String server) { this.server = server; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public boolean isDirty() { return dirty; }
    public void markDirty() { this.dirty = true; }
    public void clearDirty() { this.dirty = false; }
}
