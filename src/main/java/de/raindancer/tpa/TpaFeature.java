package de.raindancer.tpa;

import org.bukkit.Material;

import java.util.List;

/**
 * Everything this plugin can do, once.
 *
 * <h2>Why an enum and not a menu class and a command class that both know the list</h2>
 * The rule this plugin is built to keep is that <em>every feature is reachable by command and by
 * menu, equally</em> — a plugin where the menu can do something the commands cannot is a plugin
 * players ask questions about, and one where the commands can do something the menu cannot is a menu
 * nobody trusts. That property is only true if it cannot drift, so both front ends are generated from
 * this: the hub menu paints one button per constant at the slot named here, and {@code /tpa help}
 * prints one line per constant. {@code FeatureParityTest} fails the build if a constant names a
 * command that is not registered, if a registered command belongs to no constant, or if two constants
 * want the same slot.
 *
 * <p>The same shape the host's {@code Setting} catalogue and {@code Topic} manual use, and for the
 * same reason: one declaration, two front ends, no way to add to one and forget the other.
 */
public enum TpaFeature {

    ASK("ask", "Ask to teleport to somebody", Material.ENDER_PEARL, 2,
            "Asks a player to let you come to them. They have to say yes.",
            List.of("tpa")),

    ASK_HERE("ask-here", "Ask somebody to come to you", Material.ENDER_EYE, 3,
            "Asks a player to teleport to where you are standing.",
            List.of("tpahere")),

    REQUESTS("requests", "Requests waiting for you", Material.PAPER, 4,
            "Everybody who has asked, and everybody you have asked. Accept or refuse them here.",
            List.of("tpaccept", "tpdeny")),

    CANCEL("cancel", "Take your request back", Material.BARRIER, 5,
            "Withdraws the request you have out, before it is answered.",
            List.of("tpcancel")),

    BACK("back", "Go back where you were", Material.COMPASS, 6,
            "Returns you to where a teleport took you from — or to where you died.",
            List.of("back")),

    TOGGLE("toggle", "Whether people may ask you", Material.LEVER, 12,
            "Turns incoming requests off for everybody, and back on again.",
            List.of("tptoggle")),

    BLOCK("block", "People you have blocked", Material.IRON_DOOR, 14,
            "Requests from these players are refused as though you were not accepting any.",
            List.of("tpablock", "tpaunblock"));

    private final String id;
    private final String title;
    private final Material icon;
    private final int slot;
    private final String summary;
    private final List<String> commands;

    TpaFeature(String id, String title, Material icon, int slot, String summary,
               List<String> commands) {
        this.id = id;
        this.title = title;
        this.icon = icon;
        this.slot = slot;
        this.summary = summary;
        this.commands = List.copyOf(commands);
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    /** Where this button sits in the hub menu, counted from the top left. */
    public int slot() {
        return slot;
    }

    public String summary() {
        return summary;
    }

    /** The commands that do this, without their slashes. */
    public List<String> commands() {
        return commands;
    }

    /** "/tpa, /tpahere" — for the lore line under the button and for {@code /tpa help}. */
    public String commandList() {
        return commands.stream().map(command -> "/" + command)
                .reduce((first, second) -> first + ", " + second).orElse("");
    }

    /**
     * Whether this button belongs on a server with these settings.
     * <p>
     * Only {@code /back} can be switched off, and when it is, it is switched off in both front ends
     * at once — the button disappears and the command refuses. Painting a button that answers "that
     * is disabled" is a worse menu than one that does not have it.
     */
    public boolean availableUnder(TpaOptions options) {
        return this != BACK || options.backEnabled();
    }
}
