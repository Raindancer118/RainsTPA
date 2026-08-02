package de.raindancer.tpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where {@code /back} goes — and the one rule that makes it useful after dying.
 */
class ReturnsTest {

    private final Returns returns = new Returns();
    private final UUID player = UUID.randomUUID();

    private static Waypoint at(double x, Waypoint.Cause cause) {
        return new Waypoint("world", x, 64, 0, 0f, 0f, cause, 1L);
    }

    @Test
    @DisplayName("nothing to go back to until something is written down")
    void emptyToStartWith() {
        assertThat(returns.of(player)).isEmpty();
        assertThat(returns.take(player)).isEmpty();
    }

    @Test
    @DisplayName("a later teleport replaces an earlier one")
    void latestTeleportWins() {
        returns.remember(player, at(1, Waypoint.Cause.TELEPORT));
        returns.remember(player, at(2, Waypoint.Cause.TELEPORT));

        assertThat(returns.of(player)).get().extracting(Waypoint::x).isEqualTo(2.0);
    }

    /**
     * The whole reason {@link Returns} has a rule at all: respawning is itself a teleport, so without
     * this the death point is overwritten a tick after it is written and {@code /back} is useless
     * exactly when it is wanted.
     */
    @Test
    @DisplayName("a teleport does not overwrite a death that has not been used yet")
    void deathSurvivesTheRespawn() {
        returns.remember(player, at(10, Waypoint.Cause.DEATH));
        boolean recorded = returns.remember(player, at(0, Waypoint.Cause.TELEPORT));

        assertThat(recorded).isFalse();
        assertThat(returns.of(player)).get()
                .extracting(Waypoint::cause).isEqualTo(Waypoint.Cause.DEATH);
    }

    @Test
    @DisplayName("dying again does overwrite the last death")
    void deathOverwritesDeath() {
        returns.remember(player, at(10, Waypoint.Cause.DEATH));
        returns.remember(player, at(20, Waypoint.Cause.DEATH));

        assertThat(returns.of(player)).get().extracting(Waypoint::x).isEqualTo(20.0);
    }

    @Test
    @DisplayName("once it has been used, the next teleport is what /back returns to")
    void backIsReversible() {
        returns.remember(player, at(10, Waypoint.Cause.DEATH));
        assertThat(returns.take(player)).isPresent();
        assertThat(returns.of(player)).isEmpty();

        returns.remember(player, at(0, Waypoint.Cause.TELEPORT));
        assertThat(returns.of(player)).get().extracting(Waypoint::x).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a null place is not remembered")
    void nullIsIgnored() {
        assertThat(returns.remember(player, null)).isFalse();
        assertThat(returns.of(player)).isEmpty();
    }

    @Test
    @DisplayName("coordinates read the way a player writes them down")
    void coordinatesAreRounded() {
        assertThat(new Waypoint("world", 12.7, 64.2, -3.4, 0f, 0f, Waypoint.Cause.TELEPORT, 1L)
                .coordinates()).isEqualTo("13, 64, -3");
    }
}
