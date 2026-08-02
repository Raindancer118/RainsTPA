package de.raindancer.tpa;

import org.bukkit.plugin.Plugin;

/**
 * What the commands, the menus and the listeners are allowed to ask the plugin for.
 *
 * <p>An interface rather than the class, because the class is the one file that differs between the
 * standalone jar and the module inside Rain's SMP Core — everything else in the package is written
 * against this and therefore does not have to know which build it is in.
 */
public interface Tpa extends Plugin {

    /** The current settings: this plugin's own config, or the host's catalogue when folded in. */
    TpaOptions options();

    /** Requests that are still an offer. */
    TpaRequests requests();

    /** Who is accepting requests, and who they have blocked. */
    TpaStore store();

    /** Warmups, cooldowns, the teleport itself and {@code /back}. */
    TpaService teleports();

    /** Re-reads the settings. */
    void reload();
}
