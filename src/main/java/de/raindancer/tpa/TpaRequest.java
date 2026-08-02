package de.raindancer.tpa;

import java.util.UUID;

/**
 * One outstanding teleport request.
 *
 * <h2>Why names are stored beside the ids</h2>
 * A request is shown to two people, listed in a menu and named in an expiry message, and one of those
 * two may have logged off by then. Looking a name up from a {@link UUID} at that point means either
 * {@code getOfflinePlayer}, which blocks on a Mojang lookup, or "unknown player" in the one message
 * that had to say who. The name as it was when the request was made is both cheap and right.
 *
 * @param from      who asked
 * @param fromName  their name when they asked
 * @param to        who is being asked
 * @param toName    their name when they were asked
 * @param kind      which of them travels
 * @param madeAt    epoch millis when it was made
 * @param expiresAt epoch millis after which it is no longer an offer
 */
public record TpaRequest(UUID from, String fromName, UUID to, String toName, TpaKind kind,
                         long madeAt, long expiresAt) {

    public boolean isExpired(long now) {
        return now >= expiresAt;
    }

    /** Whole seconds left, floored at zero, for a lore line or a countdown. */
    public long secondsLeft(long now) {
        return Math.max(0, (expiresAt - now + 999) / 1000);
    }

    /** Whoever is not the given player, so a message can name "the other one" without a branch. */
    public UUID other(UUID player) {
        return from.equals(player) ? to : from;
    }

    public String otherName(UUID player) {
        return from.equals(player) ? toName : fromName;
    }

    /** Who ends up moving when this is accepted. */
    public UUID traveller() {
        return kind.requesterTravels() ? from : to;
    }

    /** Whose location the traveller ends up at. */
    public UUID destination() {
        return kind.requesterTravels() ? to : from;
    }
}
