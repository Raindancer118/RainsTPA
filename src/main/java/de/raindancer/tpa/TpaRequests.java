package de.raindancer.tpa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Every request that is still an offer, and the rules about which ones may exist at once.
 *
 * <h2>Why one outgoing request per player</h2>
 * A player who types {@code /tpa} at four people in a row has not made four offers — they have
 * changed their mind three times, and the first three are traps: whoever accepts an old one drags
 * somebody who has moved on. Sending a new request therefore withdraws the previous one, and the
 * player who was asked is told, because a request that silently stops working is worse than one that
 * is refused.
 *
 * <h2>Why incoming requests are <em>not</em> limited</h2>
 * Being asked is not an action the person being asked took. Capping it would mean a popular player
 * silently stops receiving requests, which looks exactly like the plugin being broken;
 * {@code /tptoggle} and the block list are the answers to unwanted requests, and both are theirs.
 *
 * <h2>Why there is a clock in here</h2>
 * Expiry is the whole point of this class and a test that has to sleep for sixty seconds is a test
 * nobody runs. Everything reads time through {@link LongSupplier}, so the entire lifecycle is
 * exercised in microseconds and no part of this file needs a server.
 */
public final class TpaRequests {

    /** from → their one outstanding request. */
    private final Map<UUID, TpaRequest> outgoing = new ConcurrentHashMap<>();

    private final LongSupplier clock;

    public TpaRequests() {
        this(System::currentTimeMillis);
    }

    public TpaRequests(LongSupplier clock) {
        this.clock = clock;
    }

    public long now() {
        return clock.getAsLong();
    }

    // ------------------------------------------------------------------ making one

    /**
     * Records a request, withdrawing whatever the same player had asked before.
     *
     * @return the request this one displaced, so the player who was asked can be told it is off
     */
    public Optional<TpaRequest> put(TpaRequest request) {
        TpaRequest displaced = outgoing.put(request.from(), request);
        if (displaced == null || displaced.isExpired(now())) {
            return Optional.empty();
        }
        return Optional.of(displaced);
    }

    /** Builds a request that runs from now to {@code seconds} from now. */
    public TpaRequest build(UUID from, String fromName, UUID to, String toName, TpaKind kind,
                            int seconds) {
        long made = now();
        return new TpaRequest(from, fromName, to, toName, kind, made, made + seconds * 1000L);
    }

    // ------------------------------------------------------------------ reading

    /** This player's own outstanding request, if it has not run out. */
    public Optional<TpaRequest> from(UUID player) {
        TpaRequest mine = outgoing.get(player);
        if (mine == null || mine.isExpired(now())) {
            return Optional.empty();
        }
        return Optional.of(mine);
    }

    /** What this player has been asked, newest first — the order the menu and {@code /tpaccept} use. */
    public List<TpaRequest> to(UUID player) {
        long now = now();
        List<TpaRequest> theirs = new ArrayList<>();
        for (TpaRequest request : outgoing.values()) {
            if (request.to().equals(player) && !request.isExpired(now)) {
                theirs.add(request);
            }
        }
        theirs.sort(Comparator.comparingLong(TpaRequest::madeAt).reversed());
        return List.copyOf(theirs);
    }

    /** Whether these two already have this exact offer open, in this direction. */
    public boolean has(UUID from, UUID to) {
        return from(from).filter(request -> request.to().equals(to)).isPresent();
    }

    // ------------------------------------------------------------------ answering

    /**
     * Takes the request this player should answer, and removes it.
     *
     * @param who  the player answering
     * @param from whose request, or {@code null} for "the most recent one"
     */
    public Optional<TpaRequest> take(UUID who, UUID from) {
        if (from != null) {
            return from(from)
                    .filter(request -> request.to().equals(who))
                    .map(request -> {
                        outgoing.remove(from, request);
                        return request;
                    });
        }
        List<TpaRequest> waiting = to(who);
        if (waiting.isEmpty()) {
            return Optional.empty();
        }
        TpaRequest newest = waiting.get(0);
        outgoing.remove(newest.from(), newest);
        return Optional.of(newest);
    }

    /** Withdraws this player's own request. */
    public Optional<TpaRequest> withdraw(UUID from) {
        Optional<TpaRequest> mine = from(from);
        mine.ifPresent(request -> outgoing.remove(from, request));
        return mine;
    }

    // ------------------------------------------------------------------ housekeeping

    /**
     * Everything that has just run out, removed.
     * <p>
     * Returned rather than simply dropped: both people were told the request existed, so both are
     * told it has stopped. A request that quietly disappears is the reason players type
     * {@code /tpaccept} into a server that has forgotten what they mean.
     */
    public List<TpaRequest> expire() {
        long now = now();
        List<TpaRequest> gone = new ArrayList<>();
        for (TpaRequest request : List.copyOf(outgoing.values())) {
            if (request.isExpired(now) && outgoing.remove(request.from(), request)) {
                gone.add(request);
            }
        }
        return List.copyOf(gone);
    }

    /**
     * Drops everything this player is part of, in either direction, and says what was dropped.
     * <p>
     * Called when they log out: an offer that cannot be answered because one end of it is gone is not
     * an offer, and leaving it in the map would let it be accepted the moment they log back in — a
     * teleport nobody asked for at that point.
     */
    public List<TpaRequest> forget(UUID player) {
        List<TpaRequest> gone = new ArrayList<>();
        for (TpaRequest request : List.copyOf(outgoing.values())) {
            if ((request.from().equals(player) || request.to().equals(player))
                    && outgoing.remove(request.from(), request)) {
                gone.add(request);
            }
        }
        return List.copyOf(gone);
    }

    /** How many offers are open right now — the startup and shutdown line, and nothing else. */
    public int size() {
        long now = now();
        return (int) outgoing.values().stream().filter(request -> !request.isExpired(now)).count();
    }

    public void clear() {
        outgoing.clear();
    }
}
