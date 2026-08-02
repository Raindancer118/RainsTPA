package de.raindancer.tpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic behind "tell both players their request ran out".
 *
 * <h2>The bug this exists to stop coming back</h2>
 * The sweep used to be booked for exactly {@code seconds × 20} ticks. A tick is the server's clock and
 * a request's expiry is the wall clock, and those are not the same clock — the sweep landed a few
 * milliseconds early, found nothing expired, and was then gone. The request lapsed silently and both
 * players were left looking at a chat line that had quietly stopped working. Found by two bots on a
 * real server; a unit test could not have found it, but it can keep it fixed.
 */
class ExpirySweepTest {

    @Test
    @DisplayName("a sweep is never booked before the thing it is sweeping has expired")
    void neverEarly() {
        for (long millis = 1; millis <= 600_000; millis += 137) {
            long ticks = TpaService.ticksUntil(millis);
            assertThat(ticks * 50)
                    .as("a request expiring in %sms is swept after %s ticks", millis, ticks)
                    .isGreaterThanOrEqualTo(millis);
        }
    }

    @Test
    @DisplayName("and never so late that a player is left wondering")
    void neverLate() {
        // A fifth of a second of slack, and no more: the message is "your request ran out", and a
        // player who has already given up and typed something else does not need it.
        for (long millis : new long[] {5_000, 60_000, 600_000}) {
            assertThat(TpaService.ticksUntil(millis) * 50 - millis).isBetween(0L, 250L);
        }
    }

    @Test
    @DisplayName("something already overdue is swept on the next tick, not never")
    void overdueIsSweptAtOnce() {
        assertThat(TpaService.ticksUntil(0)).isGreaterThanOrEqualTo(1);
        assertThat(TpaService.ticksUntil(-10_000)).isGreaterThanOrEqualTo(1);
    }
}
