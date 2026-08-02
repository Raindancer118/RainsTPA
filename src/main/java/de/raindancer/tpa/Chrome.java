package de.raindancer.tpa;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * How this plugin signs and delivers what it says — and the one seam between the standalone jar and
 * the module inside Rain's SMP Core.
 *
 * <h2>Why a seam rather than two copies</h2>
 * Folded into Rain's SMP Core, every window in the jar has to wear the host's brand and every personal
 * message has to obey the host's {@code messages.personal-in-action-bar} setting, or a player sees one
 * plugin behaving like two. Standing on its own, this plugin has neither of those to ask. Putting the
 * three decisions behind suppliers means {@code TpaPlugin} is the only file that differs between the
 * two builds — the same arrangement the homes module uses, and the reason vendoring this is a
 * one-file job. See MODULES.md in Rain's SMP Core.
 *
 * <h2>Why there is no brand in here</h2>
 * This class holds the seam, not a policy: the fallbacks below are "no tag" and "no brand", not this
 * plugin's own. The identity is chosen in {@code TpaPlugin#onEnable()} — the file that is already
 * allowed to differ — because a default written here would be a second chat tag living in the jar.
 */
public final class Chrome {

    private static volatile Supplier<String> chatPrefix = () -> "";
    private static volatile UnaryOperator<Component> titler = page ->
            page == null ? Component.empty() : page;
    private static volatile Sender sender = Audience::sendMessage;

    /** How a message that concerns only its recipient is delivered. */
    @FunctionalInterface
    public interface Sender {
        void send(Audience recipient, Component message);
    }

    private Chrome() {
    }

    /**
     * Installed once, at startup, by the plugin's main class.
     *
     * @param prefix   what every chat message starts with, as MiniMessage; null keeps the default
     * @param title    wraps a page name into a finished window title; null keeps the default
     * @param personal delivers a message that concerns only its recipient; null keeps the default
     */
    public static void configure(Supplier<String> prefix, UnaryOperator<Component> title,
                                 Sender personal) {
        if (prefix != null) {
            chatPrefix = prefix;
        }
        if (title != null) {
            titler = title;
        }
        if (personal != null) {
            sender = personal;
        }
    }

    /** What every chat message from this plugin starts with, as MiniMessage. */
    public static String prefix() {
        String configured = chatPrefix.get();
        return configured == null ? "" : configured;
    }

    /** A finished window title for a page. */
    /**
     * Where this module's colours come from.
     *
     * <p>The same seam the homes module has: standing alone it answers for itself, folded into Rain's
     * SMP Core it answers with the Appearance page, so every window in the jar changes together.
     */
    public interface Palette {

        String titleLabel();

        String titleValue();

        String separator();

        String itemName();

        String itemLore();

        String ok();

        String warn();

        String bad();

        String danger();
    }

    private static final Palette OWN = new Palette() {
        @Override
        public String titleLabel() {
            return "dark_gray";
        }

        @Override
        public String titleValue() {
            return "white";
        }

        @Override
        public String separator() {
            return "▸";
        }

        @Override
        public String itemName() {
            return "aqua";
        }

        @Override
        public String itemLore() {
            return "gray";
        }

        @Override
        public String ok() {
            return "green";
        }

        @Override
        public String warn() {
            return "yellow";
        }

        @Override
        public String bad() {
            return "red";
        }

        @Override
        public String danger() {
            return "dark_red";
        }
    };

    private static volatile Palette palette = OWN;

    public static void palette(Palette installed) {
        if (installed != null) {
            palette = installed;
        }
    }

    public static Palette palette() {
        return palette;
    }

    /** A window title as a trail, the last part being the page you are on. */
    public static String titleText(String... crumbs) {
        if (crumbs == null || crumbs.length == 0) {
            return "";
        }
        StringBuilder built = new StringBuilder("<").append(palette.titleLabel()).append('>');
        for (int index = 0; index < crumbs.length; index++) {
            if (index > 0) {
                built.append(' ').append(palette.separator()).append(' ');
            }
            if (index == crumbs.length - 1 && crumbs.length > 1) {
                built.append('<').append(palette.titleValue()).append('>');
            }
            built.append(crumbs[index]);
        }
        return built.toString();
    }

    public static Component title(Component page) {
        return titler.apply(page);
    }

    /** Handy for the many places that build a page name from plain text. */
    public static Component title(String page) {
        return title(MiniMessage.miniMessage().deserialize(page));
    }

    /**
     * Sends a message that concerns nobody but its recipient.
     * <p>
     * Inside Rain's SMP Core this is the action bar, when the message is short enough for one; on its
     * own it is chat, because a standalone plugin has no setting to obey.
     */
    public static void personal(Audience recipient, Component message) {
        sender.send(recipient, message);
    }

    /** A one-off consumer view, for code that wants to hand the sender around. */
    public static Consumer<Component> to(Audience recipient) {
        return message -> personal(recipient, message);
    }
}
