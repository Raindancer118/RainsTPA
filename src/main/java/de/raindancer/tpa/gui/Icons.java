package de.raindancer.tpa.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/**
 * The items every window in this module is built out of.
 *
 * <h2>Why the heads are set by UUID</h2>
 * {@code SkullMeta#setOwningPlayer} needs a profile or the head is Steve wearing somebody's name, and
 * {@code Bukkit.getOfflinePlayer(String)} blocks the calling thread on a Mojang lookup — which here is
 * a region thread with a server on it. By id it is a local lookup, and a face that has not been cached
 * yet simply arrives a moment later.
 */
public final class Icons {

    private Icons() {
    }

    public static ItemStack of(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** A player's own face, or a plain skull when the server has never heard of them. */
    public static ItemStack head(OfflinePlayer profile, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(profile);
        }
        if (meta != null) {
            meta.displayName(name);
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** The frame every window in this module wears along its bottom row. */
    public static ItemStack filler() {
        return of(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
    }
}
