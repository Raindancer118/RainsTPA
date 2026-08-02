package de.raindancer.tpa.gui;

import de.raindancer.tpa.Chrome;
import de.raindancer.tpa.Tpa;
import de.raindancer.tpa.TpaKind;
import de.raindancer.tpa.TpaRequest;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every offer this player is part of: what they have been asked, and what they have asked for.
 *
 * <h2>Why both directions are on one screen</h2>
 * They are the same question — "what teleports are in the air right now?" — and a player who has
 * asked somebody and been asked by somebody else would otherwise have to know that those live in two
 * different places. The row of incoming requests is above; the one request a player may have out is
 * below it, on its own, because there is only ever one.
 *
 * <h2>Why the seconds left are on the item</h2>
 * A request that expires while a menu is open is the commonest confusing moment in a plugin like
 * this. Showing the countdown makes the disappearance something the player watched happen.
 */
public final class RequestsMenu implements InventoryHolder {

    private static final int ROWS = 4;
    private static final int COLUMNS = 9;

    /** The top two rows hold what has been asked of this player. */
    private static final int INCOMING_SLOTS = 18;

    /** Where the one request they have out is drawn. */
    private static final int SLOT_OUTGOING = 22;

    private static final int SLOT_REFRESH = 3;
    private static final int SLOT_BACK = 4;
    private static final int SLOT_CLOSE = 5;

    private final Tpa plugin;
    private final Player viewer;
    private final Inventory inventory;

    /** What was drawn, so a click means the request that was under the cursor. */
    private List<TpaRequest> shown = List.of();

    public RequestsMenu(Tpa plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, ROWS * COLUMNS,
                Chrome.title(TpaText.raw(Chrome.titleText("Teleport", "Requests"))));
        paint();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public void open() {
        paint();
        viewer.openInventory(inventory);
    }

    // ------------------------------------------------------------------ painting

    private void paint() {
        inventory.clear();
        long now = plugin.requests().now();

        shown = plugin.requests().to(viewer.getUniqueId());
        for (int index = 0; index < shown.size() && index < INCOMING_SLOTS; index++) {
            inventory.setItem(index, incoming(shown.get(index), now));
        }
        if (shown.isEmpty()) {
            inventory.setItem(4, Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    TpaText.itemName("<gray>Nobody has asked you"),
                    List.of(TpaText.itemLore("Requests appear here as they arrive."))));
        }

        Optional<TpaRequest> mine = plugin.requests().from(viewer.getUniqueId());
        inventory.setItem(SLOT_OUTGOING, mine
                .map(request -> outgoing(request, now))
                .orElseGet(() -> Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                        TpaText.itemName("<gray>You have no request out"),
                        List.of(TpaText.itemLore("Ask somebody from the teleport menu.")))));

        int frame = inventory.getSize() - COLUMNS;
        ItemStack filler = Icons.filler();
        for (int slot = frame; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(frame + SLOT_REFRESH, Icons.of(Material.CLOCK,
                TpaText.itemName("<yellow>Refresh"),
                List.of(TpaText.itemLore("Redraws the countdowns."))));
        inventory.setItem(frame + SLOT_BACK, Icons.of(Material.OAK_DOOR,
                TpaText.itemName("<yellow>Back"), List.of()));
        inventory.setItem(frame + SLOT_CLOSE, Icons.of(Material.BARRIER,
                TpaText.itemName("<red>Close"), List.of()));
    }

    private ItemStack incoming(TpaRequest request, long now) {
        List<Component> lore = new ArrayList<>();
        lore.add(TpaText.itemLore(request.kind() == TpaKind.TO
                ? "Wants to come to you." : "Wants you to go to them."));
        lore.add(TpaText.itemLore("<yellow><seconds>s<reset><gray> left",
                TpaText.num("seconds", request.secondsLeft(now))));
        lore.add(Component.empty());
        lore.add(TpaText.itemLore("<green>Left-click<reset><gray> to accept"));
        lore.add(TpaText.itemLore("<red>Right-click<reset><gray> to refuse"));
        lore.add(TpaText.itemLore("<dark_gray>Shift-click to block them"));
        return Icons.head(plugin.getServer().getOfflinePlayer(request.from()),
                TpaText.itemName("<aqua><name>", TpaText.arg("name", request.fromName())), lore);
    }

    private ItemStack outgoing(TpaRequest request, long now) {
        return Icons.of(Material.ENDER_PEARL,
                TpaText.itemName("<aqua>Waiting on <name>", TpaText.arg("name", request.toName())),
                List.of(TpaText.itemLore(request.kind() == TpaKind.TO
                                ? "You asked to go to them." : "You asked them to come here."),
                        TpaText.itemLore("<yellow><seconds>s<reset><gray> left",
                                TpaText.num("seconds", request.secondsLeft(now))),
                        Component.empty(),
                        TpaText.itemLore("<yellow>Click<reset><gray> to take it back")));
    }

    // ------------------------------------------------------------------ clicking

    void handle(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(inventory)) {
            return;
        }
        int frame = inventory.getSize() - COLUMNS;
        int slot = event.getRawSlot();

        if (slot >= frame) {
            int button = slot - frame;
            if (button == SLOT_BACK) {
                new TpaMenu(plugin, viewer).open();
            } else if (button == SLOT_CLOSE) {
                viewer.closeInventory();
            } else if (button == SLOT_REFRESH) {
                paint();
            }
            return;
        }

        if (slot == SLOT_OUTGOING) {
            plugin.requests().withdraw(viewer.getUniqueId())
                    .ifPresent(request -> plugin.teleports().withdraw(viewer, request));
            paint();
            return;
        }

        if (slot < 0 || slot >= shown.size()) {
            return;
        }
        TpaRequest request = shown.get(slot);

        if (event.isShiftClick()) {
            de.raindancer.tpa.TpaCommands.block(plugin, viewer, request.from(), request.fromName());
            paint();
            return;
        }

        // Taken through the registry rather than acted on straight from the item: the request may
        // have run out or been withdrawn in the seconds this window has been open, and the registry
        // is the only thing that knows.
        Optional<TpaRequest> taken = plugin.requests().take(viewer.getUniqueId(), request.from());
        if (taken.isEmpty()) {
            TpaText.tell(viewer, TpaText.warn("That request is no longer open."));
            paint();
            return;
        }
        if (event.isRightClick()) {
            plugin.teleports().deny(viewer, taken.get());
            paint();
            return;
        }
        viewer.closeInventory();
        plugin.teleports().accept(viewer, taken.get());
    }
}
