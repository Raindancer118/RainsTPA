package de.raindancer.tpa;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where {@code /back} goes.
 *
 * <h2>Why exactly one place per player, and not a history</h2>
 * A stack of previous positions sounds better and is worse: {@code /back} then means "one step back
 * along a path I cannot see", and a player who uses it twice has no idea where they will end up. One
 * place makes the command reversible instead — {@code /back} writes down where you were as it takes
 * you away, so a second {@code /back} returns you, and the whole feature is two points you can hold
 * in your head.
 *
 * <h2>Why a death overwrites a teleport, but not the other way round</h2>
 * The one time {@code /back} really matters is after dying with a full inventory on the floor.
 * Respawning is itself a teleport, so without this rule the death point would be overwritten by the
 * respawn a tick later and the feature would be useless exactly when it is needed. A death point is
 * therefore kept until it is used or until the player is somewhere else on purpose — a completed
 * {@code /back}, or another death.
 *
 * <p>Deliberately not on disk. A {@code /back} that survives a restart takes a player to where they
 * stood before the server went down, which is rarely where they think they are going, and it means a
 * server that has been up for a month holds a location for everybody who has ever played.
 */
public final class Returns {

    private final Map<UUID, Waypoint> places = new ConcurrentHashMap<>();

    /**
     * Writes down where a player was.
     *
     * @return whether it was recorded; a teleport does not overwrite an unused death point
     */
    public boolean remember(UUID player, Waypoint where) {
        if (where == null) {
            return false;
        }
        Waypoint held = places.get(player);
        if (held != null && held.cause() == Waypoint.Cause.DEATH
                && where.cause() != Waypoint.Cause.DEATH) {
            return false;
        }
        places.put(player, where);
        return true;
    }

    public Optional<Waypoint> of(UUID player) {
        return Optional.ofNullable(places.get(player));
    }

    /** Takes the place away, because the player is about to be standing in it. */
    public Optional<Waypoint> take(UUID player) {
        return Optional.ofNullable(places.remove(player));
    }

    public void forget(UUID player) {
        places.remove(player);
    }

    public int size() {
        return places.size();
    }

    public void clear() {
        places.clear();
    }
}
