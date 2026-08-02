package de.raindancer.tpa;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Somewhere a player was, and why that is worth remembering.
 *
 * <h2>Why the world is a name and not a {@link World}</h2>
 * The same reason a home stores one: holding the world object keeps an unloaded world in the heap,
 * and a waypoint in a world that is not loaded right now should be unreachable rather than thrown
 * away. Position is stored rather than the {@link Location} it came from, because a Location holds a
 * reference to its world.
 */
public record Waypoint(String world, double x, double y, double z, float yaw, float pitch,
                       Cause cause, long at) {

    /** Why this place was written down — the difference between "where you were" and "where you died". */
    public enum Cause {
        /** Somewhere a teleport took the player away from. */
        TELEPORT("where you were"),
        /** Where the player died. */
        DEATH("where you died");

        private final String description;

        Cause(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public static Waypoint of(Location location, Cause cause, long at) {
        return new Waypoint(location.getWorld() == null ? "" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), cause, at);
    }

    /** The place itself, or {@code null} when its world is not loaded. */
    public Location location() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
    }

    public boolean isReachable() {
        return Bukkit.getWorld(world) != null;
    }

    /** "x, y, z", rounded, for a lore line. */
    public String coordinates() {
        return Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z);
    }
}
