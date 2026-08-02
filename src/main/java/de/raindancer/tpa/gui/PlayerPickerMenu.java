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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Who to ask.
 *
 * <h2>Why a menu of faces and not a list of names</h2>
 * Typing a name is the one part of {@code /tpa} that goes wrong: capital letters, a number somebody
 * remembers as a letter, a player who logged off between reading the list and typing it. A grid of
 * heads is the same request without a keyboard, and it is the only place that shows, before you ask,
 * whether the person is even accepting requests.
 *
 * <h2>Why both directions live in one window</h2>
 * A left-click asks for the direction the window was opened for and a right-click asks for the other
 * one, so the button that opened it is never contradicted and neither direction is a second window
 * away. Choosing the direction first would be the wrong way round: players decide who first.
 */
public final class PlayerPickerMenu implements InventoryHolder {

    private static final int COLUMNS = 9;
    private static final int MAX_CONTENT_ROWS = 5;

    private static final int SLOT_PREVIOUS = 0;
    private static final int SLOT_SUBJECT = 4;
    private static final int SLOT_BACK = 6;
    private static final int SLOT_NEXT = 8;

    private final Tpa plugin;
    private final Player viewer;
    private final TpaKind kind;

    private int page;
    private Inventory inventory;

    /** The players as they were when the page was painted, so a click means what it looked like. */
    private List<Player> shown = List.of();

    public PlayerPickerMenu(Tpa plugin, Player viewer, TpaKind kind) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.kind = kind;
    }

    // ------------------------------------------------------------------ opening

    public void open() {
        if (others().isEmpty()) {
            TpaText.tell(viewer, TpaText.info("There is nobody else online to ask."));
            return;
        }
        paint();
        viewer.openInventory(getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, size(),
                    Chrome.title(TpaText.raw(Chrome.titleText("Teleport", "Who?"))));
        }
        return inventory;
    }

    /** Everybody online but the viewer, in a stable order so the grid does not shuffle under a click. */
    private List<Player> others() {
        List<Player> others = new ArrayList<>();
        for (Player candidate : plugin.getServer().getOnlinePlayers()) {
            if (!candidate.getUniqueId().equals(viewer.getUniqueId())) {
                others.add(candidate);
            }
        }
        others.sort(Comparator.comparing(player -> player.getName().toLowerCase(Locale.ROOT)));
        return List.copyOf(others);
    }

    private int contentRows() {
        int needed = Math.max(1, (others().size() + COLUMNS - 1) / COLUMNS);
        return Math.min(MAX_CONTENT_ROWS, needed);
    }

    /**
     * Fixed for the lifetime of the window: a Bukkit inventory cannot be resized once created, so a
     * player logging off while the menu is open repaints at the same size and the spare slots fill
     * with the frame's glass. Reopening gives the smaller window.
     */
    private int size() {
        return (contentRows() + 1) * COLUMNS;
    }

    // ------------------------------------------------------------------ painting

    private void paint() {
        Inventory view = getInventory();
        view.clear();

        int pageSize = view.getSize() - COLUMNS;
        List<Player> all = others();
        int pages = Math.max(1, (all.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(page, pages - 1));

        int from = page * pageSize;
        shown = List.copyOf(all.subList(from, Math.min(all.size(), from + pageSize)));
        for (int index = 0; index < shown.size(); index++) {
            view.setItem(index, face(shown.get(index)));
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
        view.setItem(frame + SLOT_SUBJECT, Icons.of(kind.requesterTravels()
                        ? Material.ENDER_PEARL : Material.ENDER_EYE,
                TpaText.itemName("<aqua>Who do you want to ask?"),
                List.of(TpaText.itemLore("Page <n> of <of>",
                                TpaText.num("n", page + 1L), TpaText.num("of", pages)),
                        TpaText.itemLore("<green>Left-click<reset><gray> — <what>",
                                TpaText.arg("what", ask(kind))),
                        TpaText.itemLore("<yellow>Right-click<reset><gray> — <what>",
                                TpaText.arg("what", ask(other(kind)))))));
        view.setItem(frame + SLOT_BACK, Icons.of(Material.OAK_DOOR,
                TpaText.itemName("<yellow>Back"), List.of()));
    }

    /** The other direction, so one click is never two windows away from the one beside it. */
    private static TpaKind other(TpaKind kind) {
        return kind == TpaKind.TO ? TpaKind.HERE : TpaKind.TO;
    }

    /** How a direction reads on a button. */
    private static String ask(TpaKind kind) {
        return kind == TpaKind.TO ? "ask to go to them" : "ask them to come here";
    }

    private ItemStack face(Player other) {
        List<Component> lore = new ArrayList<>();
        lore.add(TpaText.itemLore("<world>", TpaText.arg("world", other.getWorld().getName())));
        boolean blocked = plugin.store().blocks(viewer.getUniqueId(), other.getUniqueId());
        // What their setting is, but never whether *they* have blocked *you* — the person who set
        // that block has to live beside this player afterwards.
        if (!plugin.store().isAccepting(other.getUniqueId())) {
            lore.add(TpaText.itemLore("<red>Not accepting requests right now."));
        }
        if (blocked) {
            lore.add(TpaText.itemLore("<red>You have blocked them."));
        }
        lore.add(Component.empty());
        lore.add(TpaText.itemLore("<green>Left-click<reset><gray> to <what>",
                TpaText.arg("what", ask(kind))));
        lore.add(TpaText.itemLore("<yellow>Right-click<reset><gray> to <what>",
                TpaText.arg("what", ask(other(kind)))));
        lore.add(TpaText.itemLore("<red>Shift-click<reset><gray> to "
                + (blocked ? "unblock them" : "block them")));
        return Icons.head(other,
                TpaText.itemName("<aqua><name>", TpaText.arg("name", other.getName())), lore);
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
                new TpaMenu(plugin, viewer).open();
            }
            return;
        }

        if (slot < 0 || slot >= shown.size()) {
            return;
        }
        Player other = shown.get(slot);
        UUID id = other.getUniqueId();

        if (event.isShiftClick()) {
            if (plugin.store().blocks(viewer.getUniqueId(), id)) {
                TpaCommands.unblock(plugin, viewer, id, other.getName());
            } else {
                TpaCommands.block(plugin, viewer, id, other.getName());
            }
            paint();
            return;
        }

        viewer.closeInventory();
        plugin.teleports().ask(viewer, other, event.isRightClick() ? other(kind) : kind);
    }
}
