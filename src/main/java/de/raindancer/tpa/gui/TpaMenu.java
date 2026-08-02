package de.raindancer.tpa.gui;

import de.raindancer.tpa.Chrome;
import de.raindancer.tpa.Tpa;
import de.raindancer.tpa.TpaCommands;
import de.raindancer.tpa.TpaFeature;
import de.raindancer.tpa.TpaKind;
import de.raindancer.tpa.TpaOptions;
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

/**
 * What {@code /tpa} with nothing after it opens: everything this plugin does, as buttons.
 *
 * <h2>Why the buttons are generated rather than written out</h2>
 * One button per {@link TpaFeature}, at the slot that constant names. A hand-written menu is how a
 * plugin ends up with a command that has no button — which is the one thing this module promises it
 * will not have. {@code FeatureParityTest} fails the build if a feature has no slot here or a slot is
 * claimed twice.
 */
public final class TpaMenu implements InventoryHolder {

    private static final int ROWS = 3;
    private static final int COLUMNS = 9;

    private static final int SLOT_HELP = 4;
    private static final int SLOT_CLOSE = 6;

    private final Tpa plugin;
    private final Player viewer;
    private final Inventory inventory;

    public TpaMenu(Tpa plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, ROWS * COLUMNS,
                Chrome.title(TpaText.raw(Chrome.titleText("Teleport"))));
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
        TpaOptions settings = plugin.options();
        for (TpaFeature feature : TpaFeature.values()) {
            if (!feature.availableUnder(settings)) {
                continue;
            }
            inventory.setItem(feature.slot(), button(feature, settings));
        }

        int frame = inventory.getSize() - COLUMNS;
        ItemStack filler = Icons.filler();
        for (int slot = frame; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(frame + SLOT_HELP, Icons.of(Material.WRITABLE_BOOK,
                TpaText.itemName("<aqua>How this works"),
                List.of(TpaText.itemLore("Everything here can also be typed."),
                        TpaText.itemLore("<yellow>Click<reset><gray> to have the list in chat."))));
        inventory.setItem(frame + SLOT_CLOSE, Icons.of(Material.BARRIER,
                TpaText.itemName("<red>Close"), List.of()));
    }

    /** One feature, with the state it is in right now under it. */
    private ItemStack button(TpaFeature feature, TpaOptions settings) {
        List<Component> lore = new ArrayList<>();
        lore.add(TpaText.itemLore("<what>", TpaText.arg("what", feature.summary())));
        lore.add(Component.empty());
        lore.addAll(state(feature, settings));
        lore.add(TpaText.itemLore("<dark_gray><commands>",
                TpaText.arg("commands", feature.commandList())));
        return Icons.of(feature.icon(),
                TpaText.itemName("<aqua><title>", TpaText.arg("title", feature.title())), lore);
    }

    /** What this feature is doing at the moment, so the menu is a status screen as well as a door. */
    private List<Component> state(TpaFeature feature, TpaOptions settings) {
        return switch (feature) {
            case REQUESTS -> {
                int waiting = plugin.requests().to(viewer.getUniqueId()).size();
                yield List.of(waiting == 0
                        ? TpaText.itemLore("Nobody is waiting on you.")
                        : TpaText.itemLore("<yellow><count><reset><gray> waiting for an answer",
                                TpaText.num("count", waiting)));
            }
            case CANCEL -> outgoing();
            case TOGGLE -> List.of(plugin.store().isAccepting(viewer.getUniqueId())
                    ? TpaText.itemLore("<green>On<reset><gray> — people may ask you")
                    : TpaText.itemLore("<red>Off<reset><gray> — nobody can ask you"));
            case BLOCK -> {
                int blocked = plugin.store().blockedBy(viewer.getUniqueId()).size();
                yield List.of(TpaText.itemLore("<count> blocked", TpaText.num("count", blocked)));
            }
            case BACK -> List.of(plugin.teleports().returns().of(viewer.getUniqueId())
                    .map(where -> TpaText.itemLore("<what> — <where>",
                            TpaText.arg("what", where.cause().description()),
                            TpaText.arg("where", where.coordinates())))
                    .orElse(TpaText.itemLore("Nowhere to go back to yet.")));
            case ASK, ASK_HERE -> List.of(TpaText.itemLore(
                    "Requests stand for <seconds>s.", TpaText.num("seconds", settings.requestSeconds())));
        };
    }

    private List<Component> outgoing() {
        return List.of(plugin.requests().from(viewer.getUniqueId())
                .map(request -> TpaText.itemLore("Waiting on <player>",
                        TpaText.arg("player", request.toName())))
                .orElse(TpaText.itemLore("You have no request out.")));
    }

    // ------------------------------------------------------------------ clicking

    /**
     * Every click in this window, including the player's own inventory.
     * <p>
     * Cancelled unconditionally and first: a menu made of buttons has nowhere to put an item, and a
     * shift-click from below would otherwise post one into it and lose it when the window closed.
     */
    void handle(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(inventory)) {
            return;
        }
        int frame = inventory.getSize() - COLUMNS;
        int slot = event.getRawSlot();
        if (slot == frame + SLOT_CLOSE) {
            viewer.closeInventory();
            return;
        }
        if (slot == frame + SLOT_HELP) {
            viewer.closeInventory();
            TpaCommands.help(plugin, viewer);
            return;
        }

        TpaOptions settings = plugin.options();
        for (TpaFeature feature : TpaFeature.values()) {
            if (feature.slot() != slot || !feature.availableUnder(settings)) {
                continue;
            }
            act(feature);
            return;
        }
    }

    private void act(TpaFeature feature) {
        switch (feature) {
            case ASK -> new PlayerPickerMenu(plugin, viewer, TpaKind.TO).open();
            case ASK_HERE -> new PlayerPickerMenu(plugin, viewer, TpaKind.HERE).open();
            case REQUESTS -> new RequestsMenu(plugin, viewer).open();
            case CANCEL -> {
                plugin.requests().withdraw(viewer.getUniqueId())
                        .ifPresentOrElse(request -> plugin.teleports().withdraw(viewer, request),
                                () -> TpaText.tell(viewer, TpaText.warn("You have no request out.")));
                paint();
            }
            case TOGGLE -> {
                TpaCommands.setAccepting(plugin, viewer,
                        !plugin.store().isAccepting(viewer.getUniqueId()));
                paint();
            }
            case BLOCK -> new BlockedMenu(plugin, viewer).open();
            case BACK -> {
                viewer.closeInventory();
                plugin.teleports().goBack(viewer);
            }
        }
    }
}
