package de.raindancer.tpa;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything this plugin can be typed at.
 *
 * <h2>Why the commands are built from one list</h2>
 * The plugin's promise is that every feature is reachable by command <em>and</em> by menu. Both front
 * ends therefore come from {@link TpaFeature}: the menu paints a button per constant, and the list
 * below registers the commands those constants name. {@code FeatureParityTest} fails the build if the
 * two ever stop covering each other — a command with no button, or a button with no command.
 *
 * <h2>Why the names are the ones every other server uses</h2>
 * {@code /tpa}, {@code /tpahere}, {@code /tpaccept}, {@code /tpdeny}, {@code /tpcancel},
 * {@code /tptoggle} and {@code /back} are muscle memory for anybody who has played on a server with a
 * teleport plugin before. A plugin that answers {@code /teleport request accept} instead is a plugin
 * every player has to be taught, and the aliases below cover the two or three spellings that the rest
 * of the ecosystem disagrees about.
 */
public final class TpaCommands {

    /** The node an ordinary player needs to ask anybody anything. */
    public static final String USE = "tpa.use";

    /** {@code /back}. Separate, because a server may want travel without undo. */
    public static final String BACK = "tpa.back";

    /** One command as this plugin registers it, so the two main classes register the same set. */
    public record Registration(String name, String description, List<String> aliases,
                               BasicCommand command) {
    }

    private TpaCommands() {
    }

    /**
     * Every command, ready to hand to Paper's command registrar.
     * <p>
     * One list rather than a call per command in each main class: the module and the standalone jar
     * must register the same commands, and two hand-written lists are two lists that drift.
     */
    public static List<Registration> all(Tpa plugin) {
        return List.of(
                new Registration("tpa",
                        "Ask a player to let you teleport to them — with no name, opens the menu",
                        // Only spellings of these commands, never a general word: "call" or "menu"
                        // would take a name another plugin on the server may be using for something
                        // else entirely, and an alias is registered server-wide.
                        List.of("tpask"), tpa(plugin)),
                new Registration("tpahere",
                        "Ask a player to teleport to you",
                        List.of("tphere"), tpahere(plugin)),
                new Registration("tpaccept",
                        "Accept a teleport request",
                        List.of("tpyes"), tpaccept(plugin)),
                new Registration("tpdeny",
                        "Turn a teleport request down",
                        List.of("tpno", "tpadeny"), tpdeny(plugin)),
                new Registration("tpcancel",
                        "Take your own teleport request back",
                        List.of("tpacancel"), tpcancel(plugin)),
                new Registration("tptoggle",
                        "Whether other players may ask to teleport to you",
                        List.of("tpatoggle"), tptoggle(plugin)),
                new Registration("tpablock",
                        "Refuse teleport requests from one player",
                        List.of("tpblock"), tpablock(plugin)),
                new Registration("tpaunblock",
                        "Let a blocked player ask again",
                        List.of("tpunblock"), tpaunblock(plugin)),
                new Registration("back",
                        "Go back where a teleport took you from, or where you died",
                        List.of(), back(plugin)));
    }

    // ------------------------------------------------------------------ /tpa and /tpahere

    private static BasicCommand tpa(Tpa plugin) {
        return request(plugin, TpaKind.TO, true);
    }

    private static BasicCommand tpahere(Tpa plugin) {
        return request(plugin, TpaKind.HERE, false);
    }

    /**
     * @param opensMenuWithoutArguments whether a bare command opens the hub, as {@code /home} does.
     *                                  Only {@code /tpa} does: it is the door to everything, and
     *                                  {@code /tpahere} with nobody named is a typo, not a request to
     *                                  browse
     */
    private static BasicCommand request(Tpa plugin, TpaKind kind, boolean opensMenuWithoutArguments) {
        return new BasicCommand() {
            @Override
            public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
                Player player = playerOrRefuse(source.getSender());
                if (player == null) {
                    return;
                }
                if (args.length == 0) {
                    if (opensMenuWithoutArguments) {
                        new de.raindancer.tpa.gui.TpaMenu(plugin, player).open();
                        return;
                    }
                    TpaText.tell(player, TpaText.error(
                            "Who? Type /<command> followed by a player's name.",
                            TpaText.arg("command", kind.command())));
                    return;
                }
                if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
                    help(plugin, player);
                    return;
                }
                Player target = online(plugin, args[0]);
                if (target == null) {
                    TpaText.tell(player, TpaText.error("<name> is not online.",
                            TpaText.arg("name", args[0])));
                    return;
                }
                plugin.teleports().ask(player, target, kind);
            }

            @Override
            public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                                       String @NotNull [] args) {
                return completeOnlinePlayers(plugin, source.getSender(), args);
            }

            @Override
            public String permission() {
                return USE;
            }
        };
    }

    // ------------------------------------------------------------------ /tpaccept and /tpdeny

    private static BasicCommand tpaccept(Tpa plugin) {
        return answer(plugin, true);
    }

    private static BasicCommand tpdeny(Tpa plugin) {
        return answer(plugin, false);
    }

    /**
     * Both answers, because they differ in one line.
     *
     * <h2>What a bare {@code /tpaccept} means when two people have asked</h2>
     * The most recent request, and the answer says whose it was. The alternative — refusing until the
     * player names somebody — is correct and unhelpful: the request has sixty seconds to live and the
     * player is being asked to read a name off a screen and type it. Naming who was accepted, and
     * saying how many are still waiting, gives them the same information without the delay.
     */
    private static BasicCommand answer(Tpa plugin, boolean accepting) {
        return new BasicCommand() {
            @Override
            public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
                Player player = playerOrRefuse(source.getSender());
                if (player == null) {
                    return;
                }
                UUID from = null;
                if (args.length > 0) {
                    Optional<TpaRequest> named = plugin.requests().to(player.getUniqueId()).stream()
                            .filter(request -> request.fromName().equalsIgnoreCase(args[0]))
                            .findFirst();
                    if (named.isEmpty()) {
                        TpaText.tell(player, TpaText.error("<name> has not asked you anything.",
                                TpaText.arg("name", args[0])));
                        return;
                    }
                    from = named.get().from();
                }
                Optional<TpaRequest> taken = plugin.requests().take(player.getUniqueId(), from);
                if (taken.isEmpty()) {
                    TpaText.tell(player, TpaText.warn("Nobody is waiting on an answer from you."));
                    return;
                }
                if (accepting) {
                    plugin.teleports().accept(player, taken.get());
                } else {
                    plugin.teleports().deny(player, taken.get());
                }
                int left = plugin.requests().to(player.getUniqueId()).size();
                if (left > 0) {
                    TpaText.tell(player, TpaText.info("<count> other request(s) still waiting — /tpa "
                            + "opens the list.", TpaText.num("count", left)));
                }
            }

            @Override
            public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                                       String @NotNull [] args) {
                if (!(source.getSender() instanceof Player player) || args.length > 1) {
                    return List.of();
                }
                String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return plugin.requests().to(player.getUniqueId()).stream()
                        .map(TpaRequest::fromName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                        .toList();
            }

            @Override
            public String permission() {
                return USE;
            }
        };
    }

    // ------------------------------------------------------------------ /tpcancel

    private static BasicCommand tpcancel(Tpa plugin) {
        return new BasicCommand() {
            @Override
            public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
                Player player = playerOrRefuse(source.getSender());
                if (player == null) {
                    return;
                }
                // A warmup counts as something to call off: a player who types /tpcancel while the
                // countdown is running means the teleport, not the request that is already answered.
                if (plugin.teleports().isWarmingUp(player)) {
                    plugin.teleports().cancel(player, "Cancelled — you called it off.");
                    return;
                }
                Optional<TpaRequest> mine = plugin.requests().withdraw(player.getUniqueId());
                if (mine.isEmpty()) {
                    TpaText.tell(player, TpaText.warn("You have no request out."));
                    return;
                }
                plugin.teleports().withdraw(player, mine.get());
            }

            @Override
            public String permission() {
                return USE;
            }
        };
    }

    // ------------------------------------------------------------------ /tptoggle

    private static BasicCommand tptoggle(Tpa plugin) {
        return new BasicCommand() {
            @Override
            public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
                Player player = playerOrRefuse(source.getSender());
                if (player == null) {
                    return;
                }
                boolean accepting = plugin.store().isAccepting(player.getUniqueId());
                // An argument sets it outright, so a macro or a command block can say what it means
                // rather than flipping whatever happens to be there.
                if (args.length > 0) {
                    Boolean wanted = switch (args[0].toLowerCase(Locale.ROOT)) {
                        case "on", "true", "yes" -> Boolean.TRUE;
                        case "off", "false", "no" -> Boolean.FALSE;
                        default -> null;
                    };
                    if (wanted == null) {
                        TpaText.tell(player, TpaText.error("Say 'on' or 'off', or nothing to flip it."));
                        return;
                    }
                    accepting = !wanted;
                }
                setAccepting(plugin, player, !accepting);
            }

            @Override
            public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                                       String @NotNull [] args) {
                return args.length <= 1 ? List.of("on", "off") : List.of();
            }

            @Override
            public String permission() {
                return USE;
            }
        };
    }

    /** Shared by the command and the button in the menu, so the two cannot say different things. */
    public static void setAccepting(Tpa plugin, Player player, boolean accepting) {
        plugin.store().seen(player.getUniqueId(), player.getName());
        plugin.store().setAccepting(player.getUniqueId(), accepting);
        TpaSounds.changed(player);
        TpaText.tell(player, accepting
                ? TpaText.success("Teleport requests are on — people may ask again.")
                : TpaText.warn("Teleport requests are off — nobody can ask you."));
    }

    // ------------------------------------------------------------------ /tpablock and /tpaunblock

    private static BasicCommand tpablock(Tpa plugin) {
        return new BasicCommand() {
            @Override
            public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
                Player player = playerOrRefuse(source.getSender());
                if (player == null) {
                    return;
                }
                if (args.length == 0) {
                    new de.raindancer.tpa.gui.BlockedMenu(plugin, player).open();
                    return;
                }
                OfflinePlayer target = known(plugin, args[0]);
                if (target == null) {
                    TpaText.tell(player, TpaText.error(
                            "Nobody called <name> has been seen on this server.",
                            TpaText.arg("name", args[0])));
                    return;
                }
                block(plugin, player, target.getUniqueId(), nameOf(target, args[0]));
            }

            @Override
            public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                                       String @NotNull [] args) {
                return completeOnlinePlayers(plugin, source.getSender(), args);
            }

            @Override
            public String permission() {
                return USE;
            }
        };
    }

    private static BasicCommand tpaunblock(Tpa plugin) {
        return new BasicCommand() {
            @Override
            public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
                Player player = playerOrRefuse(source.getSender());
                if (player == null) {
                    return;
                }
                if (args.length == 0) {
                    new de.raindancer.tpa.gui.BlockedMenu(plugin, player).open();
                    return;
                }
                Optional<UUID> blocked = plugin.store().blockedBy(player.getUniqueId()).stream()
                        .filter(entry -> entry.getValue().equalsIgnoreCase(args[0]))
                        .map(java.util.Map.Entry::getKey)
                        .findFirst();
                if (blocked.isEmpty()) {
                    TpaText.tell(player, TpaText.warn("<name> is not on your block list.",
                            TpaText.arg("name", args[0])));
                    return;
                }
                unblock(plugin, player, blocked.get(), args[0]);
            }

            @Override
            public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                                       String @NotNull [] args) {
                if (!(source.getSender() instanceof Player player) || args.length > 1) {
                    return List.of();
                }
                String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return plugin.store().blockedBy(player.getUniqueId()).stream()
                        .map(java.util.Map.Entry::getValue)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                        .toList();
            }

            @Override
            public String permission() {
                return USE;
            }
        };
    }

    /** Shared by the command and the menu. Blocking also takes down whatever they had asked. */
    public static void block(Tpa plugin, Player player, UUID other, String otherName) {
        if (player.getUniqueId().equals(other)) {
            TpaText.tell(player, TpaText.error("You cannot block yourself."));
            return;
        }
        if (!plugin.store().block(player.getUniqueId(), other, otherName)) {
            TpaText.tell(player, TpaText.warn("<name> is already blocked.",
                    TpaText.arg("name", otherName)));
            return;
        }
        plugin.requests().to(player.getUniqueId()).stream()
                .filter(request -> request.from().equals(other))
                .forEach(request -> plugin.requests().withdraw(request.from()));
        TpaSounds.changed(player);
        TpaText.tell(player, TpaText.success("<name> can no longer ask to teleport to you.",
                TpaText.arg("name", otherName)));
    }

    public static void unblock(Tpa plugin, Player player, UUID other, String otherName) {
        if (!plugin.store().unblock(player.getUniqueId(), other)) {
            TpaText.tell(player, TpaText.warn("<name> is not on your block list.",
                    TpaText.arg("name", otherName)));
            return;
        }
        TpaSounds.changed(player);
        TpaText.tell(player, TpaText.success("<name> may ask again.", TpaText.arg("name", otherName)));
    }

    // ------------------------------------------------------------------ /back

    private static BasicCommand back(Tpa plugin) {
        return new BasicCommand() {
            @Override
            public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
                Player player = playerOrRefuse(source.getSender());
                if (player == null) {
                    return;
                }
                plugin.teleports().goBack(player);
            }

            @Override
            public String permission() {
                return BACK;
            }
        };
    }

    // ------------------------------------------------------------------ /tpa help

    /** The same list the hub menu paints, as text — see {@link TpaFeature}. */
    public static void help(Tpa plugin, Player player) {
        TpaOptions settings = plugin.options();
        player.sendMessage(TpaText.info("Teleport requests"));
        for (TpaFeature feature : TpaFeature.values()) {
            if (!feature.availableUnder(settings)) {
                continue;
            }
            player.sendMessage(TpaText.raw("<yellow><commands><reset> <dark_gray>—<reset> <gray><what>",
                    TpaText.arg("commands", feature.commandList()),
                    TpaText.arg("what", feature.summary())));
        }
        player.sendMessage(TpaText.raw("<dark_gray>Everything here is also in the menu: <gray>/tpa"));
    }

    // ------------------------------------------------------------------ shared

    private static Player playerOrRefuse(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(TpaText.error(
                "A teleport request belongs to a player — this needs to be run in game."));
        return null;
    }

    /** Case-insensitive, because a player typing a name has no reason to get the case right. */
    private static Player online(Tpa plugin, String name) {
        for (Player candidate : plugin.getServer().getOnlinePlayers()) {
            if (candidate.getName().equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Somebody who can be blocked: online, or already known to this plugin.
     * <p>
     * Never {@code Bukkit.getOfflinePlayer(String)} — it blocks the calling thread on a Mojang lookup,
     * and the calling thread here is a region thread with a server on it.
     */
    private static OfflinePlayer known(Tpa plugin, String name) {
        Player online = online(plugin, name);
        if (online != null) {
            return online;
        }
        for (OfflinePlayer candidate : plugin.getServer().getOfflinePlayers()) {
            if (name.equalsIgnoreCase(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    private static String nameOf(OfflinePlayer player, String typed) {
        return player.getName() == null ? typed : player.getName();
    }

    /** Tab completion: who is online, minus the player asking. */
    static Collection<String> completeOnlinePlayers(Tpa plugin, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length > 1) {
            return List.of();
        }
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player candidate : plugin.getServer().getOnlinePlayers()) {
            if (!candidate.getUniqueId().equals(player.getUniqueId())
                    && candidate.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                names.add(candidate.getName());
            }
        }
        return List.copyOf(names);
    }
}
