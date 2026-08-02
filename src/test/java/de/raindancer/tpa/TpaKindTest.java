package de.raindancer.tpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two sentences a request is announced with.
 *
 * <h2>Why this is worth a test</h2>
 * The first version kept fragments — "to you" and "to come to them" — and glued them onto a shared
 * "wants to teleport". One direction read fine and the other said <em>"Buddy wants to teleport to come
 * to them"</em>, which nothing caught until two bots put a real request into real chat on a real
 * server. The clauses are whole sentences now, and this pins that they stay whole.
 */
class TpaKindTest {

    /** As {@code TpaService} builds it: the asker's name, the clause, a full stop. */
    private static String announcedTo(TpaKind kind) {
        return "Steve " + kind.asked() + ".";
    }

    @Test
    @DisplayName("each direction announces itself as one readable sentence")
    void announcementsReadAsSentences() {
        assertThat(announcedTo(TpaKind.TO)).isEqualTo("Steve wants to teleport to you.");
        assertThat(announcedTo(TpaKind.HERE)).isEqualTo("Steve wants you to teleport to them.");
    }

    @Test
    @DisplayName("what the asker is told is a clause the command can complete")
    void theAskerIsToldWhatTheyAskedFor() {
        assertThat("Asked Steve " + TpaKind.TO.asking() + ".")
                .isEqualTo("Asked Steve to let you come to them.");
        assertThat("Asked Steve " + TpaKind.HERE.asking() + ".")
                .isEqualTo("Asked Steve to come to you.");
    }

    @Test
    @DisplayName("only /tpa moves the player who asked")
    void whoTravels() {
        assertThat(TpaKind.TO.requesterTravels()).isTrue();
        assertThat(TpaKind.HERE.requesterTravels()).isFalse();
        assertThat(TpaKind.TO.command()).isEqualTo("tpa");
        assertThat(TpaKind.HERE.command()).isEqualTo("tpahere");
    }
}
