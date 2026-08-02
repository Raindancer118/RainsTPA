package de.raindancer.tpa;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the settings, including the ones somebody has typed wrong.
 */
class TpaOptionsTest {

    private static TpaOptions read(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception invalid) {
            throw new AssertionError(invalid);
        }
        return TpaOptions.from(config.getConfigurationSection("tpa"));
    }

    @Test
    @DisplayName("no section at all is the defaults, not a failure to start")
    void missingSection() {
        assertThat(TpaOptions.from(null)).isEqualTo(TpaOptions.defaults());
        assertThat(read("something-else: true")).isEqualTo(TpaOptions.defaults());
    }

    @Test
    @DisplayName("a half-written section keeps the defaults for what it does not mention")
    void partialSection() {
        TpaOptions options = read("""
                tpa:
                  warmup-seconds: 10
                """);

        assertThat(options.warmupSeconds()).isEqualTo(10);
        assertThat(options.requestSeconds()).isEqualTo(TpaOptions.defaults().requestSeconds());
        assertThat(options.backEnabled()).isTrue();
    }

    /**
     * A negative warmup is a typo, and refusing to start over it would be a worse answer than
     * treating it as "no warmup" — which is what the number means.
     */
    @Test
    @DisplayName("nonsense numbers are clamped, not rejected")
    void clamping() {
        TpaOptions options = read("""
                tpa:
                  warmup-seconds: -5
                  request-seconds: 100000
                  cooldown-seconds: -1
                  back-cooldown-seconds: 999999
                """);

        assertThat(options.warmupSeconds()).isZero();
        assertThat(options.hasWarmup()).isFalse();
        assertThat(options.requestSeconds()).isEqualTo(600);
        assertThat(options.cooldownSeconds()).isZero();
        assertThat(options.hasCooldown()).isFalse();
        assertThat(options.backCooldownSeconds()).isEqualTo(3600);
        assertThat(options.hasBackCooldown()).isTrue();
    }

    @Test
    @DisplayName("a request always stands long enough to be read")
    void requestsCannotBeInstant() {
        assertThat(read("""
                tpa:
                  request-seconds: 0
                """).requestSeconds()).isEqualTo(5);
    }
}
