package de.raindancer.tpa;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * What a teleport request sounds and looks like.
 *
 * <h2>Why this is one class and not a line here and there</h2>
 * Feedback that is scattered ends up inconsistent — two of six actions make a noise, the warmup has
 * particles but the arrival does not — and nobody notices until a player says the plugin feels half
 * finished. Everything audible or visible about requests is decided here, so it can be made to agree
 * with itself and with the homes module beside it, which uses the same sounds for the same events.
 *
 * <p>Sounds are played to a player alone rather than at their location. A teleport the whole server
 * hears is a teleport that announces where somebody went.
 */
public final class TpaSounds {

    private TpaSounds() {
    }

    /**
     * Somebody is asking. The one sound in this plugin that is allowed to interrupt: a request stands
     * for a minute and cannot be answered by a player who did not notice it.
     */
    public static void asked(Player player) {
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.2f);
    }

    /** A request was sent. */
    public static void sent(Player player) {
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.6f);
    }

    /** A request was accepted — heard by both of them. */
    public static void accepted(Player player) {
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.8f);
    }

    /** A request was refused, withdrawn or left to lapse. */
    public static void refused(Player player) {
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.5f, 0.8f);
    }

    /** A setting was flipped. */
    public static void changed(Player player) {
        player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
    }

    /** The wait has begun. */
    public static void warmupStarted(Player player) {
        player.playSound(player, Sound.BLOCK_BEACON_ACTIVATE, 0.4f, 1.8f);
    }

    /**
     * One second of the wait: a rising note and a ring of portal dust around the feet, so standing
     * still looks like something is happening rather than like nothing is.
     */
    public static void warmupTick(Player player, int secondsLeft, int secondsTotal) {
        float pitch = 0.8f + 0.6f * (1f - (float) secondsLeft / Math.max(1, secondsTotal));
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_HARP, 0.5f, pitch);
        swirl(player.getLocation(), player, 1.0);
    }

    /** The wait was broken off. */
    public static void warmupCancelled(Player player) {
        player.playSound(player, Sound.BLOCK_BEACON_DEACTIVATE, 0.4f, 1.2f);
    }

    /**
     * Departure, at the place that was left: a teleport with no sound at the origin is how people
     * conclude somebody vanished into thin air.
     */
    public static void departed(Location from) {
        if (from.getWorld() != null) {
            from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.0f);
            from.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 40,
                    0.3, 0.7, 0.3, 0.4);
        }
    }

    public static void arrived(Player player) {
        Location at = player.getLocation();
        player.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.0f);
        if (at.getWorld() != null) {
            at.getWorld().spawnParticle(Particle.REVERSE_PORTAL, at.clone().add(0, 1, 0), 40,
                    0.3, 0.7, 0.3, 0.2);
        }
    }

    /** A ring of portal dust at the player's feet, drawn only for them. */
    private static void swirl(Location at, Player who, double radius) {
        for (int step = 0; step < 12; step++) {
            double angle = 2 * Math.PI * step / 12;
            who.spawnParticle(Particle.PORTAL,
                    at.getX() + Math.cos(angle) * radius,
                    at.getY() + 0.2,
                    at.getZ() + Math.sin(angle) * radius,
                    1, 0, 0.3, 0, 0);
        }
    }
}
