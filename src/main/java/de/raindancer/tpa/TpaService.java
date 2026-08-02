package de.raindancer.tpa;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Asking, answering, and the teleport that follows — plus the wait before it and the way back.
 *
 * <h2>Why a request is answered rather than simply obeyed</h2>
 * A teleport command that needs no consent is a command that drops a stranger into your base while
 * you are asleep. Everything here exists to keep the person being teleported to in charge of it: they
 * are asked, they may refuse, they may turn asking off wholesale, and they may block one person
 * without telling them.
 *
 * <h2>Why there is a warmup</h2>
 * Without one, {@code /tpa} is a get-out-of-any-fight card, the same way {@code /home} is — and worse,
 * because the escape can be arranged in advance with a friend standing somewhere safe. Standing still
 * for a few seconds, cancelled by taking a hit, makes it a way to travel rather than a way to survive.
 * Both halves are settings, because a peaceful server has no reason to make anybody wait.
 *
 * <h2>Why the timers are the players' own schedulers</h2>
 * {@code Player#getScheduler()} is regionised on Folia and ordinary on Paper, and it cancels itself
 * when the entity goes away — which is what a player logging out mid-warmup is. That is also why
 * expiry is scheduled on the asking player rather than on a global tick: a request nobody is waiting
 * on costs nothing, and there is no server-wide task running while the plugin is idle.
 */
public final class TpaService implements Listener {

    /** Skips the standing-still wait. */
    public static final String BYPASS_WARMUP = "tpa.bypass.warmup";

    /** Skips the wait between requests. */
    public static final String BYPASS_COOLDOWN = "tpa.bypass.cooldown";

    /** Asks a player who is not accepting requests anyway. Held by nobody by default. */
    public static final String BYPASS_TOGGLE = "tpa.bypass.toggle";

    /** Ticks of slack between when a request runs out and when the sweep looks. See {@link #ticksUntil}. */
    private static final long GRACE_TICKS = 4;

    /** A warmup in progress. */
    private record Pending(Component what, Supplier<Location> destination, Location origin,
                           ScheduledTask task) {
    }

    private final Tpa plugin;
    private final Supplier<TpaOptions> options;
    private final TpaRequests requests;
    private final TpaStore store;
    private final Returns returns = new Returns();

    private final Map<UUID, Pending> warmups = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> expiries = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBack = new ConcurrentHashMap<>();

    public TpaService(Tpa plugin, Supplier<TpaOptions> options, TpaRequests requests, TpaStore store) {
        this.plugin = plugin;
        this.options = options;
        this.requests = requests;
        this.store = store;
    }

    public Returns returns() {
        return returns;
    }

    // ------------------------------------------------------------------ asking

    /**
     * Puts a request to somebody, or says why not.
     * <p>
     * Every refusal is answered: a command that does nothing and says nothing reads as a broken
     * plugin, and a player will type it again.
     *
     * @return whether the request was made
     */
    public boolean ask(Player from, Player to, TpaKind kind) {
        TpaOptions settings = options.get();

        if (from.getUniqueId().equals(to.getUniqueId())) {
            TpaText.tell(from, TpaText.error("You are already where you are."));
            return false;
        }
        if (!settings.allowCrossWorld() && !from.getWorld().equals(to.getWorld())) {
            TpaText.tell(from, TpaText.error(
                    "<player> is in another world, and teleports across worlds are switched off here.",
                    TpaText.arg("player", to.getName())));
            return false;
        }
        // One message for "not accepting" and for "has blocked you", on purpose: a block a player can
        // detect is a block they can take personally, and the person who set it has to live beside
        // them afterwards.
        boolean refusing = !store.isAccepting(to.getUniqueId())
                || store.blocks(to.getUniqueId(), from.getUniqueId());
        if (refusing && !from.hasPermission(BYPASS_TOGGLE)) {
            TpaSounds.refused(from);
            TpaText.tell(from, TpaText.warn("<player> is not accepting teleport requests right now.",
                    TpaText.arg("player", to.getName())));
            return false;
        }
        if (requests.has(from.getUniqueId(), to.getUniqueId())) {
            TpaText.tell(from, TpaText.warn("You have already asked <player> — they have not answered yet.",
                    TpaText.arg("player", to.getName())));
            return false;
        }

        long waitLeft = cooldownRemaining(from, settings);
        if (waitLeft > 0) {
            TpaText.tell(from, TpaText.warn("Another <seconds>s before you can send another request.",
                    TpaText.num("seconds", waitLeft)));
            return false;
        }

        store.seen(from.getUniqueId(), from.getName());
        store.seen(to.getUniqueId(), to.getName());

        TpaRequest request = requests.build(from.getUniqueId(), from.getName(),
                to.getUniqueId(), to.getName(), kind, settings.requestSeconds());
        requests.put(request).ifPresent(displaced -> withdrawn(displaced, from));
        lastRequest.put(from.getUniqueId(), System.currentTimeMillis());

        announce(request, from, to, settings);
        scheduleExpiry(from, settings);
        return true;
    }

    /** Tells both people the offer exists, and gives the one being asked the two buttons. */
    private void announce(TpaRequest request, Player from, Player to, TpaOptions settings) {
        TpaSounds.sent(from);
        if (settings.notifySoundEnabled()) {
            TpaSounds.asked(to);
        }

        TpaText.tell(from, TpaText.success("Asked <player> <what>. It stands for <seconds>s.",
                TpaText.arg("player", to.getName()),
                TpaText.arg("what", request.kind().asking()),
                TpaText.num("seconds", settings.requestSeconds())));
        from.sendMessage(TpaText.raw("<gray>  ")
                .append(TpaText.button("<yellow>[Take it back]</yellow>", "/tpcancel",
                        "Withdraws the request before it is answered")));

        // Chat, not the action bar, and deliberately not through the personal-message seam: this is
        // the one message in the plugin that has to be *clicked*, and a click event on the action bar
        // does nothing at all. It is also the message that must not fade before it is read.
        to.sendMessage(TpaText.raw(Chrome.prefix() + "<aqua><name><reset><aqua> <what>.",
                TpaText.arg("name", from.getName()),
                TpaText.arg("what", request.kind().asked())));
        to.sendMessage(TpaText.raw("<gray>  ")
                .append(TpaText.button("<green>[Accept]</green>", "/tpaccept " + from.getName(),
                        "Teleports " + (request.kind() == TpaKind.TO ? from.getName() + " to you"
                                : "you to " + from.getName())))
                .append(TpaText.raw("<gray>   "))
                .append(TpaText.button("<red>[Refuse]</red>", "/tpdeny " + from.getName(),
                        "Turns the request down"))
                .append(TpaText.raw("<gray>   "))
                .append(TpaText.button("<dark_gray>[All requests]</dark_gray>", "/tpa",
                        "Opens the teleport menu")));
    }

    /**
     * The expiry, scheduled on the asking player.
     * <p>
     * Lazy expiry alone would be correct — nothing can accept a request that has run out — but nobody
     * would be told, and a player staring at a chat line that has quietly stopped working is the
     * commonest complaint about every plugin that does this.
     */
    private void scheduleExpiry(Player from, TpaOptions settings) {
        sweepIn(from, ticksUntil(settings.requestSeconds() * 1000L));
    }

    /**
     * How long to wait before sweeping something that runs out in {@code millis}.
     *
     * <h2>Why there is a grace at all</h2>
     * A tick is the server's clock and {@code expiresAt} is the wall clock, and the two are not the
     * same clock. Waiting exactly {@code seconds × 20} ticks landed the sweep a handful of
     * milliseconds <em>before</em> the request had expired: {@link TpaRequests#expire()} found nothing
     * to do, the task was gone, and the request then sat there having quietly stopped working with
     * neither player ever told. Two bots on a real server found that; nothing else could have.
     */
    static long ticksUntil(long millis) {
        return Math.max(1L, (millis + 49) / 50 + GRACE_TICKS);
    }

    /**
     * One sweep, scheduled on this player.
     * <p>
     * It sweeps <em>everything</em> that has run out rather than only this player's request: two can
     * lapse in the same second, and the other one's own timer belongs to a player who may since have
     * logged off — in which case nobody would ever be told about it. And it books the next sweep
     * itself if anything is still standing, so a request made while a sweep was already pending
     * cannot be left without one.
     */
    private void sweepIn(Player from, long ticks) {
        ScheduledTask previous = expiries.remove(from.getUniqueId());
        if (previous != null) {
            previous.cancel();
        }
        ScheduledTask task = from.getScheduler().runDelayed(plugin, scheduled -> {
            expiries.remove(from.getUniqueId());
            requests.expire().forEach(this::lapsed);
            requests.from(from.getUniqueId()).ifPresent(still ->
                    sweepIn(from, ticksUntil(still.expiresAt() - System.currentTimeMillis())));
        }, () -> expiries.remove(from.getUniqueId()), ticks);
        if (task != null) {
            expiries.put(from.getUniqueId(), task);
        }
    }

    // ------------------------------------------------------------------ answering

    /** Accepts a request. The caller has already checked it is theirs to accept. */
    public void accept(Player answering, TpaRequest request) {
        TpaOptions settings = options.get();
        Player traveller = plugin.getServer().getPlayer(request.traveller());
        Player destination = plugin.getServer().getPlayer(request.destination());
        if (traveller == null || destination == null) {
            TpaText.tell(answering, TpaText.error("<player> is not online any more.",
                    TpaText.arg("player", request.otherName(answering.getUniqueId()))));
            return;
        }
        if (!settings.allowCrossWorld() && !traveller.getWorld().equals(destination.getWorld())) {
            TpaText.tell(answering, TpaText.error(
                    "You are in different worlds now, and teleports across worlds are switched off here."));
            TpaText.tell(traveller, TpaText.error(
                    "That request cannot be answered any more — you are in different worlds."));
            return;
        }

        TpaSounds.accepted(answering);
        TpaSounds.accepted(traveller);
        Player other = answering.getUniqueId().equals(request.from()) ? destination : traveller;
        TpaText.tell(answering, TpaText.success("Accepted <player>'s request.",
                TpaText.arg("player", request.otherName(answering.getUniqueId()))));
        if (!other.getUniqueId().equals(answering.getUniqueId())) {
            TpaText.tell(other, TpaText.success("<player> accepted.",
                    TpaText.arg("player", answering.getName())));
        }

        // The destination is read when the teleport actually happens, not now: the player being
        // travelled to keeps walking during the warmup, and arriving where they stood five seconds
        // ago is how somebody ends up in the lava they just walked around.
        travel(traveller, destination::getLocation,
                TpaText.raw("<aqua><name>", TpaText.arg("name", destination.getName())), settings);
    }

    /** Turns a request down. */
    public void deny(Player answering, TpaRequest request) {
        TpaSounds.refused(answering);
        TpaText.tell(answering, TpaText.warn("Refused <player>'s request.",
                TpaText.arg("player", request.fromName())));
        Player asker = plugin.getServer().getPlayer(request.from());
        if (asker != null) {
            TpaSounds.refused(asker);
            TpaText.tell(asker, TpaText.warn("<player> turned your request down.",
                    TpaText.arg("player", answering.getName())));
        }
    }

    /** Withdraws a request its own sender no longer wants. */
    public void withdraw(Player sender, TpaRequest request) {
        TpaText.tell(sender, TpaText.warn("Took your request to <player> back.",
                TpaText.arg("player", request.toName())));
        withdrawn(request, sender);
    }

    /** Tells the player who was asked that an offer has gone away. */
    private void withdrawn(TpaRequest request, Player sender) {
        Player asked = plugin.getServer().getPlayer(request.to());
        if (asked != null) {
            TpaText.tell(asked, TpaText.warn("<player> took their teleport request back.",
                    TpaText.arg("player", sender == null ? request.fromName() : sender.getName())));
        }
    }

    /** Both ends of a request that ran out are told, because both were told it existed. */
    public void lapsed(TpaRequest request) {
        Player asker = plugin.getServer().getPlayer(request.from());
        if (asker != null) {
            TpaText.tell(asker, TpaText.warn("Your request to <player> ran out.",
                    TpaText.arg("player", request.toName())));
        }
        Player asked = plugin.getServer().getPlayer(request.to());
        if (asked != null) {
            TpaText.tell(asked, TpaText.warn("<player>'s teleport request ran out.",
                    TpaText.arg("player", request.fromName())));
        }
    }

    // ------------------------------------------------------------------ going back

    /** {@code /back}: the place a teleport took this player away from, or where they died. */
    public void goBack(Player player) {
        TpaOptions settings = options.get();
        if (!settings.backEnabled()) {
            TpaText.tell(player, TpaText.error("Going back is switched off on this server."));
            return;
        }
        Optional<Waypoint> where = returns.of(player.getUniqueId());
        if (where.isEmpty()) {
            TpaText.tell(player, TpaText.warn("There is nowhere to go back to yet."));
            return;
        }
        Waypoint waypoint = where.get();
        if (!waypoint.isReachable()) {
            TpaText.tell(player, TpaText.error("That place is in <world>, which is not loaded right now.",
                    TpaText.arg("world", waypoint.world())));
            return;
        }
        if (!settings.allowCrossWorld()
                && !waypoint.world().equals(player.getWorld().getName())) {
            TpaText.tell(player, TpaText.error(
                    "That place is in another world, and teleports across worlds are switched off here."));
            return;
        }
        long waitLeft = backCooldownRemaining(player, settings);
        if (waitLeft > 0) {
            TpaText.tell(player, TpaText.warn("Another <seconds>s before you can use /back again.",
                    TpaText.num("seconds", waitLeft)));
            return;
        }

        returns.take(player.getUniqueId());
        lastBack.put(player.getUniqueId(), System.currentTimeMillis());
        travel(player, waypoint::location,
                TpaText.raw("<aqua><what>", TpaText.arg("what", waypoint.cause().description())),
                settings);
    }

    // ------------------------------------------------------------------ the wait and the teleport

    /**
     * Takes a player somewhere, after the wait if there is one.
     *
     * @param destination read at the moment of the teleport, never before it
     * @param what        what to call the destination in the countdown, already coloured
     */
    private void travel(Player player, Supplier<Location> destination, Component what,
                        TpaOptions settings) {
        cancel(player, null);
        if (!settings.hasWarmup() || bypasses(player, BYPASS_WARMUP, settings)) {
            arrive(player, destination, what);
            return;
        }

        Location origin = player.getLocation().clone();
        int total = settings.warmupSeconds();
        int[] left = {total};

        player.sendActionBar(countdown(what, left[0]));
        TpaSounds.warmupStarted(player);

        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> {
            Pending mine = warmups.get(player.getUniqueId());
            if (mine == null || !player.isOnline()) {
                scheduled.cancel();
                return;
            }
            left[0]--;
            if (left[0] > 0) {
                player.sendActionBar(countdown(what, left[0]));
                TpaSounds.warmupTick(player, left[0], total);
                return;
            }
            scheduled.cancel();
            warmups.remove(player.getUniqueId());
            arrive(player, mine.destination(), mine.what());
        }, () -> warmups.remove(player.getUniqueId()), 20L, 20L);

        if (task == null) {
            // The entity was already gone. Nothing to wait for, and nothing to clean up.
            return;
        }
        warmups.put(player.getUniqueId(), new Pending(what, destination, origin, task));
    }

    private Component countdown(Component what, int secondsLeft) {
        return TpaText.raw("<green>Teleporting to ")
                .append(what)
                .append(TpaText.raw("<green> in <yellow><seconds>s<reset><gray> — stand still",
                        TpaText.num("seconds", secondsLeft)));
    }

    private void arrive(Player player, Supplier<Location> destination, Component what) {
        Location target = destination.get();
        if (target == null || target.getWorld() == null) {
            TpaText.tell(player, TpaText.error("There is nowhere to go any more."));
            return;
        }
        Location from = player.getLocation().clone();
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
                .whenComplete((arrived, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(arrived)) {
                        // Refused by something else — a closed dimension in Rain's SMP Core, a world
                        // border, another plugin. That thing has said why; saying so again would be
                        // two refusals for one command.
                        return;
                    }
                    TpaSounds.departed(from);
                    TpaSounds.arrived(player);
                    player.sendActionBar(TpaText.raw("<green>Arrived — ").append(what));
                });
    }

    /** Stops a warmup and says so. Safe to call when there is none. */
    public void cancel(Player player, String why) {
        Pending mine = warmups.remove(player.getUniqueId());
        if (mine == null) {
            return;
        }
        mine.task().cancel();
        TpaSounds.warmupCancelled(player);
        player.sendActionBar(TpaText.actionBar("<red>Teleport cancelled"));
        if (why != null) {
            TpaText.tell(player, TpaText.warn(why));
        }
    }

    public boolean isWarmingUp(Player player) {
        return warmups.containsKey(player.getUniqueId());
    }

    // ------------------------------------------------------------------ waits between uses

    /**
     * Whether this player skips a wait.
     *
     * <p>Holding the node is the ordinary way. Being an operator is <em>not</em>, unless the server
     * has asked for that with {@code tpa.operators-bypass} — the nodes themselves default to nobody,
     * so that an admin testing the warmup sees the warmup rather than concluding it is broken.
     */
    private static boolean bypasses(Player player, String node, TpaOptions settings) {
        return player.hasPermission(node) || (settings.operatorsBypass() && player.isOp());
    }

    /** Seconds still to wait before another request, or 0. */
    public long cooldownRemaining(Player player, TpaOptions settings) {
        return remaining(lastRequest.get(player.getUniqueId()), settings.cooldownSeconds(),
                !settings.hasCooldown() || bypasses(player, BYPASS_COOLDOWN, settings));
    }

    /** Seconds still to wait before another {@code /back}, or 0. */
    public long backCooldownRemaining(Player player, TpaOptions settings) {
        return remaining(lastBack.get(player.getUniqueId()), settings.backCooldownSeconds(),
                !settings.hasBackCooldown() || bypasses(player, BYPASS_COOLDOWN, settings));
    }

    private static long remaining(Long last, int seconds, boolean exempt) {
        if (exempt || last == null) {
            return 0;
        }
        long elapsed = (System.currentTimeMillis() - last) / 1000L;
        return Math.max(0, seconds - elapsed);
    }

    // ------------------------------------------------------------------ what cancels a warmup

    /**
     * Only leaving the block counts.
     * <p>
     * {@code PlayerMoveEvent} fires for turning the head, so comparing the whole location would cancel
     * the warmup of anybody who looked around — which reads as the feature being broken rather than as
     * a rule. {@code MONITOR}: this decides nothing about the move itself.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // The cheap question first: reading the settings parses a dozen config keys, and almost
        // always nobody on the server is warming up at all.
        Pending mine = warmups.isEmpty() ? null : warmups.get(event.getPlayer().getUniqueId());
        if (mine == null || !options.get().cancelOnMove()) {
            return;
        }
        if (sameBlock(mine.origin(), event.getTo())) {
            return;
        }
        cancel(event.getPlayer(), "Cancelled — you moved.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !warmups.containsKey(player.getUniqueId())
                || !options.get().cancelOnDamage()) {
            return;
        }
        cancel(player, "Cancelled — you took damage.");
    }

    // ------------------------------------------------------------------ where /back goes

    /**
     * Every teleport that a player asked for, rather than walked into.
     *
     * <h2>Why by cause and not by every teleport there is</h2>
     * {@code /back} means "undo the last jump". An ender pearl, a nether portal, a chorus fruit and a
     * gateway are all things the player travelled through on purpose and can walk back through, and
     * remembering them would mean {@code /back} usually undoes the wrong thing. A command or a plugin
     * moving somebody — {@code /home}, {@code /tpa}, a warp, an admin's {@code /tp} — is the case it
     * is for, and it includes this plugin's own teleports, so nothing here writes the waypoint twice.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        boolean deliberate = cause == PlayerTeleportEvent.TeleportCause.PLUGIN
                || cause == PlayerTeleportEvent.TeleportCause.COMMAND
                || cause == PlayerTeleportEvent.TeleportCause.SPECTATE;
        if (!deliberate || !options.get().backEnabled()) {
            return;
        }
        Location from = event.getFrom();
        if (from.getWorld() == null || sameBlock(from, event.getTo())) {
            return;
        }
        returns.remember(event.getPlayer().getUniqueId(),
                Waypoint.of(from, Waypoint.Cause.TELEPORT, System.currentTimeMillis()));
    }

    /** Dying is the one place {@code /back} is really for; it outranks a teleport. See {@link Returns}. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        TpaOptions settings = options.get();
        if (!settings.backEnabled() || !settings.backOnDeath()) {
            return;
        }
        Player player = event.getEntity();
        if (!player.hasPermission(TpaCommands.BACK)) {
            return;
        }
        returns.remember(player.getUniqueId(),
                Waypoint.of(player.getLocation(), Waypoint.Cause.DEATH, System.currentTimeMillis()));
    }

    // ------------------------------------------------------------------ coming and going

    /** So a name is known for the block list without a Mojang lookup. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        store.seen(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    /**
     * Leaving takes every offer this player is part of with it.
     * <p>
     * The cooldowns are only forgotten once they have run out — dropping them on quit would make
     * logging out and back in the way to skip one, which is not a cooldown. The {@code /back} point is
     * kept for the session: it is in memory only, so it goes when the server does.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Pending mine = warmups.remove(player.getUniqueId());
        if (mine != null) {
            mine.task().cancel();
        }
        ScheduledTask expiry = expiries.remove(player.getUniqueId());
        if (expiry != null) {
            expiry.cancel();
        }
        for (TpaRequest dropped : requests.forget(player.getUniqueId())) {
            Player other = plugin.getServer().getPlayer(dropped.other(player.getUniqueId()));
            if (other != null) {
                TpaText.tell(other, TpaText.warn("<player> logged off, so that teleport request is off.",
                        TpaText.arg("player", player.getName())));
            }
        }
        TpaOptions settings = options.get();
        if (cooldownRemaining(player, settings) <= 0) {
            lastRequest.remove(player.getUniqueId());
        }
        if (backCooldownRemaining(player, settings) <= 0) {
            lastBack.remove(player.getUniqueId());
        }
        returns.forget(player.getUniqueId());
    }

    static boolean sameBlock(Location origin, Location now) {
        if (origin == null || now == null || origin.getWorld() == null || now.getWorld() == null) {
            return false;
        }
        return origin.getWorld().equals(now.getWorld())
                && origin.getBlockX() == now.getBlockX()
                && origin.getBlockY() == now.getBlockY()
                && origin.getBlockZ() == now.getBlockZ();
    }

    /** Called from {@code onDisable}: a warmup outliving the plugin would teleport into nothing. */
    public void cancelAll() {
        warmups.values().forEach(mine -> mine.task().cancel());
        warmups.clear();
        expiries.values().forEach(ScheduledTask::cancel);
        expiries.clear();
        requests.clear();
    }
}
