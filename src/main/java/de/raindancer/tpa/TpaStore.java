package de.raindancer.tpa;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What each player has decided about being asked, in memory and one file behind.
 *
 * <h2>Why this is on disk at all</h2>
 * A request block and "leave me alone" are answers to another player's behaviour, and forgetting them
 * on restart means the person who was being pestered has to notice, again, that they are being
 * pestered. Requests themselves are deliberately <em>not</em> saved: an offer that outlives the
 * server the two players were on is not an offer.
 *
 * <h2>Why the writes go through one thread of this class's own</h2>
 * The same reasons as the homes module's store, and the same shape, so a bug found in one is a bug
 * findable in the other: a preference is written from whichever region thread the player is on — on
 * Folia genuinely several — and read again by the menu, the commands and the request check. Saving on
 * the calling thread puts a file write in the middle of a tick, and saving on the server's async
 * scheduler lets two saves interleave into a file that is half of each. A single-threaded executor
 * owned here gives both: writes never block a tick, and they happen in the order the changes did.
 *
 * <p>The file is written to a temporary and moved into place, so a crash mid-write costs the last
 * change rather than everybody's settings.
 */
public final class TpaStore {

    /** One player's answer to being asked. */
    public record Prefs(boolean accepting, Set<UUID> blocked) {

        public static Prefs defaults() {
            return new Prefs(true, Set.of());
        }

        public Prefs accepting(boolean value) {
            return new Prefs(value, blocked);
        }

        public boolean blocks(UUID other) {
            return blocked.contains(other);
        }

        /** Whether this is worth a line in the file at all. */
        public boolean isDefault() {
            return accepting && blocked.isEmpty();
        }
    }

    private final Path file;
    private final Logger logger;

    private final Map<UUID, Prefs> prefs = new ConcurrentHashMap<>();

    /** Last known name per blocked player, so the block list can be read without a Mojang lookup. */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RainsTPA-save");
        thread.setDaemon(true);
        return thread;
    });

    public TpaStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ reading

    public Prefs of(UUID player) {
        return prefs.getOrDefault(player, Prefs.defaults());
    }

    public boolean isAccepting(UUID player) {
        return of(player).accepting();
    }

    public boolean blocks(UUID player, UUID other) {
        return of(player).blocks(other);
    }

    /** The names this player has blocked, in the order they read best: alphabetically. */
    public List<Map.Entry<UUID, String>> blockedBy(UUID player) {
        List<Map.Entry<UUID, String>> blocked = new ArrayList<>();
        for (UUID other : of(player).blocked()) {
            blocked.add(Map.entry(other, nameOf(other)));
        }
        blocked.sort(Comparator.comparing(entry -> entry.getValue().toLowerCase(java.util.Locale.ROOT)));
        return List.copyOf(blocked);
    }

    /** The name written down when this player was last seen, or the short form of their id. */
    public String nameOf(UUID player) {
        String known = names.get(player);
        return known == null || known.isBlank() ? player.toString().substring(0, 8) : known;
    }

    /** Everybody this player has blocked, whether or not a name is known for them. */
    public Set<UUID> blockedIds(UUID player) {
        return Set.copyOf(of(player).blocked());
    }

    public int playersWithSettings() {
        return (int) prefs.values().stream().filter(entry -> !entry.isDefault()).count();
    }

    // ------------------------------------------------------------------ writing

    /** Remembers a name, so a block list can be read out later without a Mojang lookup. */
    public void seen(UUID player, String name) {
        if (name != null && !name.isBlank() && !name.equals(names.get(player))) {
            names.put(player, name);
            // Not persisted on its own: a name is only interesting beside a preference, and saving
            // on every join would write the file once per player per boot for no gain.
        }
    }

    /** Sets whether this player is open to being asked. Returns what it now is. */
    public boolean setAccepting(UUID player, boolean accepting) {
        update(player, current -> current.accepting(accepting));
        return accepting;
    }

    /** @return true when this call added the block, false when it was already there */
    public boolean block(UUID player, UUID other, String otherName) {
        if (player.equals(other) || blocks(player, other)) {
            return false;
        }
        seen(other, otherName);
        update(player, current -> {
            Set<UUID> blocked = new java.util.LinkedHashSet<>(current.blocked());
            blocked.add(other);
            return new Prefs(current.accepting(), Set.copyOf(blocked));
        });
        return true;
    }

    /** @return true when this call removed a block */
    public boolean unblock(UUID player, UUID other) {
        if (!blocks(player, other)) {
            return false;
        }
        update(player, current -> {
            Set<UUID> blocked = new java.util.LinkedHashSet<>(current.blocked());
            blocked.remove(other);
            return new Prefs(current.accepting(), Set.copyOf(blocked));
        });
        return true;
    }

    private void update(UUID player, java.util.function.UnaryOperator<Prefs> change) {
        Prefs updated = change.apply(of(player));
        if (updated.isDefault()) {
            // A player back at the defaults is a player with nothing worth writing down.
            prefs.remove(player);
        } else {
            prefs.put(player, updated);
        }
        persist();
    }

    // ------------------------------------------------------------------ disk

    public void load() {
        prefs.clear();
        names.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        int skipped = 0;
        for (String rawId : players.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(rawId);
            } catch (IllegalArgumentException notAnId) {
                skipped++;
                continue;
            }
            ConfigurationSection entry = players.getConfigurationSection(rawId);
            if (entry == null) {
                continue;
            }
            String name = entry.getString("name", "");
            if (!name.isBlank()) {
                names.put(id, name);
            }
            Set<UUID> blocked = new java.util.LinkedHashSet<>();
            for (String rawBlocked : entry.getStringList("blocked")) {
                try {
                    blocked.add(UUID.fromString(rawBlocked));
                } catch (IllegalArgumentException notAnId) {
                    skipped++;
                }
            }
            ConfigurationSection blockedNames = entry.getConfigurationSection("blocked-names");
            if (blockedNames != null) {
                for (String rawId2 : blockedNames.getKeys(false)) {
                    try {
                        names.putIfAbsent(UUID.fromString(rawId2), blockedNames.getString(rawId2, ""));
                    } catch (IllegalArgumentException notAnId) {
                        skipped++;
                    }
                }
            }
            Prefs loaded = new Prefs(entry.getBoolean("accepting", true), Set.copyOf(blocked));
            if (!loaded.isDefault()) {
                prefs.put(id, loaded);
            }
        }
        if (skipped > 0) {
            logger.warning(skipped + " entr(y/ies) in " + file.getFileName()
                    + " could not be read and were left alone; everything else loaded.");
        }
    }

    private void persist() {
        Map<UUID, Prefs> snapshot = new LinkedHashMap<>(prefs);
        Map<UUID, String> knownNames = Map.copyOf(names);
        submit(() -> write(snapshot, knownNames));
    }

    private void submit(Runnable task) {
        if (writer.isShutdown()) {
            // Shutting down: a change made after the last flush is a change made while the server
            // was already gone, so it is written here rather than queued for a thread that has
            // stopped taking work.
            task.run();
            return;
        }
        writer.execute(task);
    }

    private void write(Map<UUID, Prefs> snapshot, Map<UUID, String> knownNames) {
        YamlConfiguration yaml = new YamlConfiguration();
        snapshot.forEach((id, entry) -> {
            String base = "players." + id;
            String name = knownNames.get(id);
            if (name != null && !name.isBlank()) {
                yaml.set(base + ".name", name);
            }
            yaml.set(base + ".accepting", entry.accepting());
            if (!entry.blocked().isEmpty()) {
                yaml.set(base + ".blocked", entry.blocked().stream().map(UUID::toString).toList());
                // The names of the blocked, beside the ids: a block list an admin cannot read is a
                // block list nobody can support, and the alternative is a Mojang lookup per entry.
                for (UUID blocked : entry.blocked()) {
                    String blockedName = knownNames.get(blocked);
                    if (blockedName != null && !blockedName.isBlank()) {
                        yaml.set(base + ".blocked-names." + blocked, blockedName);
                    }
                }
            }
        });
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".writing");
            Files.writeString(temporary, yaml.saveToString());
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            logger.log(Level.SEVERE, "Could not save teleport settings to " + file
                    + "; they are still in memory and the next change will try again.", failure);
        }
    }

    /** Flushes and stops the writer. Blocks briefly: a shutdown must not lose the last change. */
    public void close() {
        persist();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Teleport settings were still being written when the server shut down.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Only for tests and for the reload path: the file as it stands, re-read. */
    public Optional<Prefs> raw(UUID player) {
        return Optional.ofNullable(prefs.get(player));
    }
}
