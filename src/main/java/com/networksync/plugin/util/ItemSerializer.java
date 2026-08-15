package com.networksync.plugin.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Sérialisation/désérialisation des données d'inventaire.
 *
 * ItemStack :
 *  - utilise Paper serializeAsBytes()
 *  - conserve les données complètes de l'item
 *  - compatible avec les items custom dont les données sont stockées dans l'ItemStack
 *
 * PotionEffect :
 *  - utilise un format binaire propriétaire simple
 *  - aucune sérialisation Java/ObjectOutputStream
 *  - compressé en GZIP
 */
public final class ItemSerializer {

    private static final int MAX_ARRAY_SIZE = 256;
    private static final int MAX_ITEM_DATA_SIZE = 4 * 1024 * 1024;
    private static final int MAX_POTION_EFFECTS = 128;

    private ItemSerializer() {
    }

    /**
     * Sérialise un tableau d'ItemStack.
     *
     * Les slots vides sont conservés afin de préserver exactement
     * la position des items dans l'inventaire.
     */
    public static byte[] serializeArray(ItemStack[] items) {

        if (items == null) {
            return serializeArray(new ItemStack[0]);
        }

        if (items.length > MAX_ARRAY_SIZE) {
            throw new IllegalArgumentException(
                    "Nombre de slots invalide : " + items.length
            );
        }

        try (
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                GZIPOutputStream gzip = new GZIPOutputStream(baos);
                DataOutputStream out = new DataOutputStream(gzip)
        ) {

            out.writeInt(items.length);

            for (ItemStack item : items) {

                if (item == null || item.getType().isAir()) {
                    out.writeBoolean(false);
                    continue;
                }

                out.writeBoolean(true);

                byte[] raw = item.serializeAsBytes();

                if (raw.length <= 0 || raw.length > MAX_ITEM_DATA_SIZE) {
                    throw new IOException(
                            "Données ItemStack invalides : " + raw.length + " octets"
                    );
                }

                out.writeInt(raw.length);
                out.write(raw);
            }

            /*
             * IMPORTANT :
             * on ferme le DataOutputStream via try-with-resources.
             *
             * Cela force :
             * DataOutputStream.flush()
             * puis GZIPOutputStream.finish()
             *
             * avant de récupérer le contenu du ByteArrayOutputStream.
             */
            out.flush();

            // On ferme manuellement GZIP ici avant de lire baos.
            gzip.finish();

            return baos.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException(
                    "Erreur de sérialisation des ItemStack",
                    e
            );
        }
    }

    /**
     * Désérialise un tableau produit par serializeArray().
     */
    public static ItemStack[] deserializeArray(byte[] data) {

        if (data == null || data.length == 0) {
            return new ItemStack[0];
        }

        try (
                ByteArrayInputStream bais = new ByteArrayInputStream(data);
                GZIPInputStream gzip = new GZIPInputStream(bais);
                DataInputStream in = new DataInputStream(gzip)
        ) {

            int size = in.readInt();

            if (size < 0 || size > MAX_ARRAY_SIZE) {
                throw new IOException(
                        "Nombre de slots invalide : " + size
                );
            }

            ItemStack[] items = new ItemStack[size];

            for (int i = 0; i < size; i++) {

                boolean present = in.readBoolean();

                if (!present) {
                    items[i] = null;
                    continue;
                }

                int len = in.readInt();

                if (len <= 0 || len > MAX_ITEM_DATA_SIZE) {
                    throw new IOException(
                            "Taille ItemStack invalide : " + len
                    );
                }

                byte[] raw = new byte[len];

                in.readFully(raw);

                items[i] = ItemStack.deserializeBytes(raw);
            }

            return items;

        } catch (IOException | RuntimeException e) {
            throw new RuntimeException(
                    "Erreur de désérialisation des ItemStack",
                    e
            );
        }
    }

    /**
     * Sérialise un seul ItemStack.
     */
    public static byte[] serializeSingle(ItemStack item) {
        return serializeArray(new ItemStack[]{item});
    }

    /**
     * Désérialise un seul ItemStack.
     */
    public static ItemStack deserializeSingle(byte[] data) {

        ItemStack[] arr = deserializeArray(data);

        return arr.length > 0 ? arr[0] : null;
    }

    /**
     * Sérialise les effets actifs du joueur.
     *
     * Format :
     *
     * [nombre]
     *
     * Pour chaque effet :
     * [type UUID/string]
     * [amplificateur]
     * [durée]
     * [ambient]
     * [particles]
     * [icon]
     *
     * Le tout est compressé en GZIP.
     */
    public static byte[] serializePotionEffects(List<PotionEffect> effects) {

        if (effects == null) {
            effects = List.of();
        }

        if (effects.size() > MAX_POTION_EFFECTS) {
            throw new IllegalArgumentException(
                    "Nombre d'effets invalide : " + effects.size()
            );
        }

        try (
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                GZIPOutputStream gzip = new GZIPOutputStream(baos);
                DataOutputStream out = new DataOutputStream(gzip)
        ) {

            out.writeInt(effects.size());

            for (PotionEffect effect : effects) {

                if (effect == null) {
                    out.writeBoolean(false);
                    continue;
                }

                out.writeBoolean(true);

                /*
                 * On stocke le type de potion sous forme de NamespacedKey.
                 *
                 * Exemple :
                 * minecraft:speed
                 */
                String key = effect.getType().getKey().toString();

                out.writeUTF(key);

                out.writeInt(effect.getAmplifier());
                out.writeInt(effect.getDuration());

                out.writeBoolean(effect.isAmbient());
                out.writeBoolean(effect.hasParticles());
                out.writeBoolean(effect.hasIcon());
            }

            out.flush();
            gzip.finish();

            return baos.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException(
                    "Erreur de sérialisation des effets de potion",
                    e
            );
        }
    }

    /**
     * Désérialise les effets de potion.
     */
    public static List<PotionEffect> deserializePotionEffects(byte[] data) {

        List<PotionEffect> result = new ArrayList<>();

        if (data == null || data.length == 0) {
            return result;
        }

        try (
                ByteArrayInputStream bais = new ByteArrayInputStream(data);
                GZIPInputStream gzip = new GZIPInputStream(bais);
                DataInputStream in = new DataInputStream(gzip)
        ) {

            int size = in.readInt();

            if (size < 0 || size > MAX_POTION_EFFECTS) {
                throw new IOException(
                        "Nombre d'effets invalide : " + size
                );
            }

            for (int i = 0; i < size; i++) {

                boolean present = in.readBoolean();

                if (!present) {
                    continue;
                }

                String keyString = in.readUTF();

                int amplifier = in.readInt();
                int duration = in.readInt();

                boolean ambient = in.readBoolean();
                boolean particles = in.readBoolean();
                boolean icon = in.readBoolean();

                /*
                 * Récupération du type Bukkit à partir du NamespacedKey.
                 */
                org.bukkit.NamespacedKey key =
                        org.bukkit.NamespacedKey.fromString(keyString);

                if (key == null) {
                    throw new IOException(
                            "NamespacedKey invalide : " + keyString
                    );
                }

                org.bukkit.Registry<PotionEffectType> registry =
                        org.bukkit.Registry.POTION_EFFECT_TYPE;

                PotionEffectType type = registry.get(key);

                if (type == null) {
                    /*
                     * L'effet n'existe plus sur cette version.
                     *
                     * On l'ignore plutôt que de faire planter
                     * toute la restauration de l'inventaire.
                     */
                    continue;
                }

                PotionEffect effect = new PotionEffect(
                        type,
                        duration,
                        amplifier,
                        ambient,
                        particles,
                        icon
                );

                result.add(effect);
            }

            return result;

        } catch (IOException | RuntimeException e) {
            throw new RuntimeException(
                    "Erreur de désérialisation des effets de potion",
                    e
            );
        }
    }

    /**
     * Applique l'inventaire complet au joueur.
     *
     * Contenu :
     *  - inventaire principal
     *  - armure
     *  - offhand
     */
    public static void applyToInventory(
            PlayerInventory inv,
            ItemStack[] contents,
            ItemStack[] armor,
            ItemStack offhand
    ) {

        if (contents != null) {
            inv.setContents(contents);
        }

        if (armor != null) {
            inv.setArmorContents(armor);
        }

        inv.setItemInOffHand(
                offhand != null
                        ? offhand
                        : new ItemStack(org.bukkit.Material.AIR)
        );
    }
}