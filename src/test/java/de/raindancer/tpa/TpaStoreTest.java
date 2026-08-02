package de.raindancer.tpa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file, which is the part that can quietly lose somebody's answer to being pestered.
 */
class TpaStoreTest {

    private static final Logger QUIET = Logger.getLogger(TpaStoreTest.class.getName());

    @TempDir
    Path folder;

    private Path file;
    private TpaStore store;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        file = folder.resolve("tpa.yml");
        store = new TpaStore(file, QUIET);
        store.load();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    @DisplayName("a missing file means everybody is accepting and nobody is blocked")
    void missingFile() {
        assertThat(store.isAccepting(alice)).isTrue();
        assertThat(store.blocks(alice, bob)).isFalse();
        assertThat(store.blockedBy(alice)).isEmpty();
    }

    @Test
    @DisplayName("what was set comes back, including after a reload from disk")
    void roundTrip() {
        store.seen(bob, "Bob");
        store.setAccepting(alice, false);
        store.block(alice, bob, "Bob");
        store.close();

        TpaStore reopened = new TpaStore(file, QUIET);
        reopened.load();
        try {
            assertThat(reopened.isAccepting(alice)).isFalse();
            assertThat(reopened.blocks(alice, bob)).isTrue();
            assertThat(reopened.blockedBy(alice)).singleElement()
                    .extracting(java.util.Map.Entry::getValue).isEqualTo("Bob");
        } finally {
            reopened.close();
        }
    }

    @Test
    @DisplayName("a player back at the defaults leaves nothing behind in the file")
    void defaultsAreNotWritten() throws Exception {
        store.setAccepting(alice, false);
        store.setAccepting(alice, true);
        store.close();

        assertThat(Files.readString(file)).doesNotContain(alice.toString());
    }

    @Test
    @DisplayName("blocking twice changes nothing, and unblocking what is not blocked says so")
    void blockingIsIdempotent() {
        assertThat(store.block(alice, bob, "Bob")).isTrue();
        assertThat(store.block(alice, bob, "Bob")).isFalse();
        assertThat(store.unblock(alice, bob)).isTrue();
        assertThat(store.unblock(alice, bob)).isFalse();
    }

    @Test
    @DisplayName("nobody can block themselves")
    void noSelfBlock() {
        assertThat(store.block(alice, alice, "Alice")).isFalse();
        assertThat(store.blockedBy(alice)).isEmpty();
    }

    @Test
    @DisplayName("a name is remembered so a block list can be read without a Mojang lookup")
    void namesAreRemembered() {
        store.seen(bob, "Bob");
        assertThat(store.nameOf(bob)).isEqualTo("Bob");
        // Never blank: an unknown player still has to render as something in a menu.
        assertThat(store.nameOf(alice)).isNotBlank();
    }

    @Test
    @DisplayName("a file with nonsense in it loads everything else and warns about the rest")
    void badEntriesAreSkipped() throws Exception {
        Files.writeString(file, """
                players:
                  not-a-uuid:
                    accepting: false
                  %s:
                    accepting: false
                    blocked:
                      - also-not-a-uuid
                      - %s
                """.formatted(alice, bob));

        TpaStore reopened = new TpaStore(file, QUIET);
        reopened.load();
        try {
            assertThat(reopened.isAccepting(alice)).isFalse();
            assertThat(reopened.blocks(alice, bob)).isTrue();
            assertThat(reopened.blockedIds(alice)).hasSize(1);
        } finally {
            reopened.close();
        }
    }
}
