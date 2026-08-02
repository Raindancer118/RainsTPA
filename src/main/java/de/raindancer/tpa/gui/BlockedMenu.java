package de.raindancer.tpa.gui;

import de.raindancer.tpa.Chrome;
import de.raindancer.tpa.Tpa;
import de.raindancer.tpa.TpaCommands;
import de.raindancer.tpa.TpaKind;
import de.raindancer.tpa.TpaText;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The people this player will not be asked by.
 *
 * <h2>Why blocking is a list you can see</h2>
 * A block nobody can review is a block that is forgotten, and then the person who set it wonders for
 * a month why a friend never asks to visit. Every entry is one click from being lifted, and the same
 * two operations are {@code /tpablock} and {@code /tpaunblock} — the list here is the same data, not
 * a second one.
 *
 * <p>Adding to it is deliberately not a text field: names are typed wrong, and everybody worth
 * blocking has just asked you something or is standing in the player list. Shift-click a face in
 * either of those windows, or type the command.
 */
public final class BlockedMenu implements InventoryHolder {

    private static final int COLUMNS = 9;
    private static final int MAX_CONTENT_ROWS = 5;

    private static final int SLOT_PREVIOUS = 0;
    private static final int SLOT_SUBJECT = 4;
    private static final int SLOT_BACK = 6;
    private static final int SLOT_NEXT = 8;

    private final Tpa plugin;
    private final Player viewer;

    private int page;
    private Inventory inventory;

    /** What was drawn, so a click lifts the block that was under the cursor. */
    private List<Map.Entry<UUID, String>> shown = List.of();

    public BlockedMenu(Tpa plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    public void open() {
        paint();
        viewer.openInventory(getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, size(),
                    Chrome.title(TpaText.raw(Chrome.titleText("Teleport", "Blocked"))));
        }
        return inventory;
    }

    private int size() {
        int blocked = plugin.store().blockedBy(viewer.getUniqueId()).size();
        int needed = Math.max(1, (blocked + COLUMNS - 1) / COLUMNS);
        return (Math.min(MAX_CONTENT_ROWS, needed) + 1) * COLUMNS;
    }

    // ------------------------------------------------------------------ painting

    private void paint() {
        Inventory view = getInventory();
        view.clear();

        int pageSize = view.getSize() - COLUMNS;
        List<Map.Entry<UUID, String>> all = plugin.store().blockedBy(viewer.getUniqueId());
        int pages = Math.max(1, (all.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(page, pages - 1));

        int from = page * pageSize;
        shown = List.copyOf(all.subList(from, Math.min(all.size(), from + pageSize)));
        for (int index = 0; index < shown.size(); index++) {
            Map.Entry<UUID, String> blocked = shown.get(index);
            view.setItem(index, Icons.head(plugin.getServer().getOfflinePlayer(blocked.getKey()),
                    TpaText.itemName("<aqua><name>", TpaText.arg("name", blocked.getValue())),
                    List.of(TpaText.itemLore("Cannot ask to teleport to you."),
                            Component.empty(),
                            TpaText.itemLore("<green>Click<reset><gray> to let them ask again"))));
        }
        if (all.isEmpty()) {
            view.setItem(4, Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    TpaText.itemName("<gray>Nobody is blocked"),
                    List.of(TpaText.itemLore("Shift-click somebody in the player list,"),
                            TpaText.itemLore("or use /tpablock <name>."))));
        }

        int frame = view.getSize() - COLUMNS;
        ItemStack filler = Icons.filler();
        for (int slot = frame; slot < view.getSize(); slot++) {
            view.setItem(slot, filler);
        }
        if (page > 0) {
            view.setItem(frame + SLOT_PREVIOUS, Icons.of(Material.ARROW,
                    TpaText.itemName("<yellow>Previous page"), List.of()));
        }
        if (page < pages - 1) {
            view.setItem(frame + SLOT_NEXT, Icons.of(Material.ARROW,
                    TpaText.itemName("<yellow>Next page"), List.of()));
        }
        view.setItem(frame + SLOT_SUBJECT, Icons.of(Material.IRON_DOOR,
                TpaText.itemName("<aqua>Blocked players"),
                List.of(TpaText.itemLore("<count> blocked", TpaText.num("count", all.size())),
                        TpaText.itemLore("Page <n> of <of>",
                                TpaText.num("n", page + 1L), TpaText.num("of", pages)),
                        Component.empty(),
                        TpaText.itemLore("They are told the same thing as anybody"),
                        TpaText.itemLore("asking somebody who is not accepting."))));
        view.setItem(frame + SLOT_BACK, Icons.of(Material.PLAYER_HEAD,
                TpaText.itemName("<yellow>Somebody to block"),
                List.of(TpaText.itemLore("Opens the player list; shift-click a face there."))));
    }

    // ------------------------------------------------------------------ clicking

    void handle(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(getInventory())) {
            return;
        }
        int frame = getInventory().getSize() - COLUMNS;
        int slot = event.getRawSlot();

        if (slot >= frame) {
            int button = slot - frame;
            if (button == SLOT_PREVIOUS && page > 0) {
                page--;
                paint();
            } else if (button == SLOT_NEXT) {
                page++;
                paint();
            } else if (button == SLOT_BACK) {
                new PlayerPickerMenu(plugin, viewer, TpaKind.TO).open();
            }
            return;
        }

        if (slot < 0 || slot >= shown.size()) {
            return;
        }
        Map.Entry<UUID, String> blocked = shown.get(slot);
        TpaCommands.unblock(plugin, viewer, blocked.getKey(), blocked.getValue());
        paint();
    }
}
