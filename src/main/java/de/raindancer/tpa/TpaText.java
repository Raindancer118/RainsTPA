package de.raindancer.tpa;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * All of this plugin's user-facing text.
 *
 * <p>Everything is MiniMessage; anything a player supplied — a name — goes in as
 * {@link Placeholder#unparsed}, so a player called {@code <red>} is five characters rather than a
 * colour change. Player names cannot contain markup today; this is what makes sure a future name
 * rule, or a nickname plugin, cannot open that hole.
 *
 * <h2>The palette is the jar's, not this plugin's</h2>
 * The vanilla colour names, exactly as the homes and claims windows use them — aqua for the subject
 * of a sentence, green for something that worked, yellow for a warning, red for a refusal, grey for
 * detail. A bespoke palette is what makes one jar look like three plugins, and
 * {@code MenuPaletteTest} in Rain's SMP Core fails the build over hex colours in a window.
 */
public final class TpaText {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    static final String OK = "green";
    static final String WARN = "yellow";
    static final String BAD = "red";
    static final String TEXT = "aqua";
    static final String MUTED = "gray";

    private TpaText() {
    }

    public static Component raw(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(miniMessage, resolvers);
    }

    public static Component info(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(Chrome.prefix() + "<" + TEXT + ">" + miniMessage, resolvers);
    }

    public static Component success(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(Chrome.prefix() + "<" + OK + ">" + miniMessage, resolvers);
    }

    public static Component warn(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(Chrome.prefix() + "<" + WARN + ">" + miniMessage, resolvers);
    }

    public static Component error(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(Chrome.prefix() + "<" + BAD + ">" + miniMessage, resolvers);
    }

    /** Wraps untrusted text so it can be dropped into a message safely. */
    public static TagResolver arg(String name, String value) {
        return Placeholder.unparsed(name, value == null ? "—" : value);
    }

    public static TagResolver num(String name, long value) {
        return Placeholder.unparsed(name, Long.toString(value));
    }

    /**
     * A line for over the hotbar. No prefix: the action bar is one short line and a tag would eat a
     * third of it.
     */
    public static Component actionBar(String miniMessage, TagResolver... resolvers) {
        return raw(miniMessage, resolvers);
    }

    /** An item's name: not italic, because Minecraft italicises custom names by default. */
    public static Component itemName(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize("<!italic>" + miniMessage, resolvers);
    }

    public static Component itemLore(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize("<!italic><" + MUTED + ">" + miniMessage, resolvers)
                .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * A word in a message that runs a command when it is clicked.
     *
     * <h2>Why a request is answered by clicking, not only by typing</h2>
     * The one message a player has to act on within sixty seconds is the one that must not require
     * them to read a name off the screen and type it correctly. The command still exists and does the
     * same thing — this is the same door, at the place the player is already looking.
     */
    public static Component button(String label, String command, String tooltip) {
        return raw(label)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(raw("<" + MUTED + ">" + tooltip)));
    }

    /** Sends something only this recipient cares about — the action bar, where that is configured. */
    public static void tell(Audience recipient, Component message) {
        Chrome.personal(recipient, message);
    }
}
