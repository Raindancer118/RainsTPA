package de.raindancer.tpa;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Rain's TPA.
 *
 * <p>{@code /tpa} asks somebody to let you come to them, {@code /tpahere} asks them to come to you,
 * and nothing moves until they say yes. {@code /tpaccept}, {@code /tpdeny} and {@code /tpcancel}
 * answer; {@code /tptoggle} and {@code /tpablock} decide who may ask at all; {@code /back} undoes the
 * last jump. Every one of those is also a button in {@code /tpa}.
 *
 * <p>Wiring only. Which requests may exist is in {@link TpaRequests}, the waiting and the teleport are
 * in {@link TpaService}, the settings are in {@link TpaOptions} and the file is {@link TpaStore} —
 * none of which need a server to test.
 *
 * <p>This class is the only file in the package that differs between this jar and the {@code tpa}
 * module of Rain's SMP Core; {@link Chrome} is the seam that makes that true. The module hands its
 * chat tag, its window titles and its colours to the host; standing alone, this plugin answers all
 * three itself — the tag below, and {@code Chrome}'s own palette.
 */
public final class TpaPlugin extends JavaPlugin implements Tpa {

    /** The plugin's own accent, matched to the rest of the Rain's family of plugins. */
    private static final String ACCENT = "#63D4C4";
    private static final String ACCENT_DIM = "#2E8177";
    private static final String TAG = "TPA";

    /**
     * Volatile because the commands read it from whichever region thread the player is on, while a
     * reload writes it from the thread that ran the command.
     */
    private volatile TpaOptions options = TpaOptions.defaults();

    private TpaRequests requests;
    private TpaStore store;
    private TpaService teleports;

    @Override
    public void onEnable() {
        // Before anything can draw a window or send a message. This is the plugin's identity, and it
        // is set here rather than in Chrome because Chrome is the seam the module replaces — a tag
        // written into it would be a second identity travelling with the vendored copy.
        Chrome.configure(TpaPlugin::tag, TpaPlugin::windowTitle, null);

        saveDefaultConfig();
        reload();

        store = new TpaStore(getDataFolder().toPath().resolve("tpa.yml"), getLogger());
        store.load();
        requests = new TpaRequests();
        teleports = new TpaService(this, this::options, requests, store);

        getServer().getPluginManager().registerEvents(teleports, this);
        getServer().getPluginManager().registerEvents(new de.raindancer.tpa.gui.MenuListener(), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var commands = event.registrar();
            for (TpaCommands.Registration registration : TpaCommands.all(this)) {
                commands.register(registration.name(), registration.description(),
                        registration.aliases(), registration.command());
            }
        });

        getLogger().info("Teleport requests are up; " + store.playersWithSettings()
                + " player(s) have settings of their own.");
    }

    @Override
    public void onDisable() {
        if (teleports != null) {
            // A warmup that outlived the plugin would fire into a server that no longer has one.
            teleports.cancelAll();
        }
        if (store != null) {
            store.close();
        }
    }

    @Override
    public TpaOptions options() {
        return options;
    }

    @Override
    public TpaRequests requests() {
        return requests;
    }

    @Override
    public TpaStore store() {
        return store;
    }

    @Override
    public TpaService teleports() {
        return teleports;
    }

    @Override
    public void reload() {
        reloadConfig();
        options = TpaOptions.from(getConfig().getConfigurationSection("tpa"));
    }

    /** The tag in front of every chat message, as MiniMessage. */
    private static String tag() {
        return gradient() + " <dark_gray>»</dark_gray> ";
    }

    /** A window title: the tag, then what the page is. */
    private static Component windowTitle(Component page) {
        Component brand = MiniMessage.miniMessage().deserialize(gradient());
        if (page == null) {
            return brand;
        }
        return brand.append(MiniMessage.miniMessage().deserialize("<dark_gray> » ")).append(page);
    }

    private static String gradient() {
        return "<gradient:" + ACCENT + ":" + ACCENT_DIM + "><bold>" + TAG + "</bold></gradient>";
    }
}
