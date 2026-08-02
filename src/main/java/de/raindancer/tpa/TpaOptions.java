package de.raindancer.tpa;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Everything the teleport-request feature can be told to do.
 *
 * <h2>Why this is a record and not a set of config lookups</h2>
 * The standalone plugin reads these from its own {@code config.yml}; folded into Rain's SMP Core it
 * reads them from the host's {@code /smpadmin} catalogue instead — how long a request stands and how
 * long somebody has to stand still are server rules an admin tunes, not implementation details. Both
 * produce one of these, so nothing downstream knows or cares which, and the main class stays the only
 * file that differs between the two builds. See MODULES.md in Rain's SMP Core.
 *
 * @param requestSeconds  how long a request stands before it lapses
 * @param warmupSeconds   seconds of standing still before the teleport happens
 * @param cancelOnMove    whether leaving the block cancels the warmup
 * @param cancelOnDamage  whether being hurt cancels the warmup
 * @param cooldownSeconds seconds before the same player may send another request
 * @param allowCrossWorld whether a request may cross a world boundary
 * @param operatorsBypass whether an operator skips the warmup and the cooldown without holding the
 *                        nodes for it. Off, because an admin who silently bypasses a feature is the
 *                        one person who cannot test it
 * @param backEnabled     whether {@code /back} exists at all
 * @param backOnDeath     whether dying counts as somewhere {@code /back} returns to
 * @param backCooldownSeconds seconds before the same player may use {@code /back} again
 * @param notifySoundEnabled whether an incoming request makes a noise for the player being asked
 */
public record TpaOptions(int requestSeconds, int warmupSeconds, boolean cancelOnMove,
                         boolean cancelOnDamage, int cooldownSeconds, boolean allowCrossWorld,
                         boolean operatorsBypass, boolean backEnabled, boolean backOnDeath,
                         int backCooldownSeconds, boolean notifySoundEnabled) {

    public static TpaOptions defaults() {
        return new TpaOptions(60, 3, true, true, 5, true, false, true, true, 10, true);
    }

    /** Reads the standalone plugin's own {@code config.yml}, or the host's {@code tpa:} section. */
    public static TpaOptions from(ConfigurationSection config) {
        if (config == null) {
            return defaults();
        }
        TpaOptions fallback = defaults();
        return new TpaOptions(
                clamp(config.getInt("request-seconds", fallback.requestSeconds()), 5, 600),
                clamp(config.getInt("warmup-seconds", fallback.warmupSeconds()), 0, 60),
                config.getBoolean("cancel-on-move", fallback.cancelOnMove()),
                config.getBoolean("cancel-on-damage", fallback.cancelOnDamage()),
                clamp(config.getInt("cooldown-seconds", fallback.cooldownSeconds()), 0, 3600),
                config.getBoolean("allow-cross-world", fallback.allowCrossWorld()),
                config.getBoolean("operators-bypass", fallback.operatorsBypass()),
                config.getBoolean("back-enabled", fallback.backEnabled()),
                config.getBoolean("back-on-death", fallback.backOnDeath()),
                clamp(config.getInt("back-cooldown-seconds", fallback.backCooldownSeconds()), 0, 3600),
                config.getBoolean("notify-sound", fallback.notifySoundEnabled()));
    }

    /**
     * Clamped rather than rejected: a warmup of -5 is a typo, and refusing to start over it would be
     * a worse answer than treating it as "no warmup".
     */
    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    public boolean hasWarmup() {
        return warmupSeconds > 0;
    }

    public boolean hasCooldown() {
        return cooldownSeconds > 0;
    }

    public boolean hasBackCooldown() {
        return backCooldownSeconds > 0;
    }
}
