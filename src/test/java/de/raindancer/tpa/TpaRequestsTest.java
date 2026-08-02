package de.raindancer.tpa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules about which offers may exist at once, and what happens when they run out.
 *
 * <p>Every one of these would otherwise need two players, a server and a minute of waiting. The clock
 * is a field, so a request's whole life happens between two lines.
 */
class TpaRequestsTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private TpaRequests requests;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        requests = new TpaRequests(clock::get);
    }

    private TpaRequest ask(UUID from, String fromName, UUID to, String toName) {
        TpaRequest request = requests.build(from, fromName, to, toName, TpaKind.TO, 60);
        requests.put(request);
        return request;
    }

    @Test
    @DisplayName("a request is waiting for the player it was put to, and for nobody else")
    void aRequestReachesOnePlayer() {
        ask(alice, "Alice", bob, "Bob");

        assertThat(requests.to(bob)).hasSize(1);
        assertThat(requests.to(carol)).isEmpty();
        assertThat(requests.from(alice)).isPresent();
        assertThat(requests.from(bob)).isEmpty();
        assertThat(requests.has(alice, bob)).isTrue();
        assertThat(requests.has(bob, alice)).isFalse();
    }

    @Test
    @DisplayName("asking somebody else withdraws the first request, and says so")
    void oneOutgoingAtATime() {
        ask(alice, "Alice", bob, "Bob");
        TpaRequest second = requests.build(alice, "Alice", carol, "Carol", TpaKind.HERE, 60);

        Optional<TpaRequest> displaced = requests.put(second);

        assertThat(displaced).isPresent();
        assertThat(displaced.get().to()).isEqualTo(bob);
        assertThat(requests.to(bob)).isEmpty();
        assertThat(requests.to(carol)).hasSize(1);
    }

    @Test
    @DisplayName("being asked by several people is allowed, newest first")
    void severalIncoming() {
        ask(bob, "Bob", alice, "Alice");
        clock.addAndGet(1000);
        ask(carol, "Carol", alice, "Alice");

        assertThat(requests.to(alice)).extracting(TpaRequest::fromName)
                .containsExactly("Carol", "Bob");
    }

    @Test
    @DisplayName("a bare answer takes the most recent request")
    void answeringWithoutNaming() {
        ask(bob, "Bob", alice, "Alice");
        clock.addAndGet(1000);
        ask(carol, "Carol", alice, "Alice");

        Optional<TpaRequest> taken = requests.take(alice, null);

        assertThat(taken).isPresent();
        assertThat(taken.get().fromName()).isEqualTo("Carol");
        // The other one is untouched: answering one request is not answering all of them.
        assertThat(requests.to(alice)).extracting(TpaRequest::fromName).containsExactly("Bob");
    }

    @Test
    @DisplayName("naming somebody takes that request, and only when it is addressed to you")
    void answeringByName() {
        ask(bob, "Bob", alice, "Alice");
        ask(carol, "Carol", alice, "Alice");

        assertThat(requests.take(alice, bob)).isPresent();
        assertThat(requests.take(alice, bob)).isEmpty();
        // Carol asked Alice, not Bob — Bob cannot answer it.
        assertThat(requests.take(bob, carol)).isEmpty();
        assertThat(requests.to(alice)).hasSize(1);
    }

    @Test
    @DisplayName("a request that has run out is not there any more, before anybody sweeps it")
    void expiryIsImmediateOnRead() {
        ask(alice, "Alice", bob, "Bob");
        clock.addAndGet(60_000);

        assertThat(requests.from(alice)).isEmpty();
        assertThat(requests.to(bob)).isEmpty();
        assertThat(requests.take(bob, null)).isEmpty();
        assertThat(requests.take(bob, alice)).isEmpty();
    }

    @Test
    @DisplayName("expire() hands back everything that lapsed, once, so both ends are told once")
    void expireReportsWhatLapsed() {
        ask(alice, "Alice", bob, "Bob");
        ask(carol, "Carol", bob, "Bob");

        assertThat(requests.expire()).isEmpty();

        clock.addAndGet(60_001);
        List<TpaRequest> lapsed = requests.expire();

        assertThat(lapsed).extracting(TpaRequest::fromName).containsExactlyInAnyOrder("Alice", "Carol");
        assertThat(requests.expire()).isEmpty();
        assertThat(requests.size()).isZero();
    }

    @Test
    @DisplayName("seconds left counts down and never goes below zero")
    void secondsLeft() {
        TpaRequest request = ask(alice, "Alice", bob, "Bob");

        assertThat(request.secondsLeft(clock.get())).isEqualTo(60);
        clock.addAndGet(59_500);
        assertThat(request.secondsLeft(clock.get())).isEqualTo(1);
        clock.addAndGet(10_000);
        assertThat(request.secondsLeft(clock.get())).isZero();
    }

    @Test
    @DisplayName("logging off drops every request the player is part of, in both directions")
    void forgetting() {
        ask(alice, "Alice", bob, "Bob");
        ask(carol, "Carol", alice, "Alice");

        List<TpaRequest> dropped = requests.forget(alice);

        assertThat(dropped).hasSize(2);
        assertThat(requests.size()).isZero();
        assertThat(requests.to(bob)).isEmpty();
    }

    @Test
    @DisplayName("withdrawing takes only your own request")
    void withdrawing() {
        ask(alice, "Alice", bob, "Bob");

        assertThat(requests.withdraw(bob)).isEmpty();
        assertThat(requests.withdraw(alice)).isPresent();
        assertThat(requests.withdraw(alice)).isEmpty();
    }

    @Test
    @DisplayName("who travels and who is travelled to depends only on the kind")
    void directions() {
        TpaRequest to = requests.build(alice, "Alice", bob, "Bob", TpaKind.TO, 60);
        TpaRequest here = requests.build(alice, "Alice", bob, "Bob", TpaKind.HERE, 60);

        assertThat(to.traveller()).isEqualTo(alice);
        assertThat(to.destination()).isEqualTo(bob);
        assertThat(here.traveller()).isEqualTo(bob);
        assertThat(here.destination()).isEqualTo(alice);
        assertThat(to.other(alice)).isEqualTo(bob);
        assertThat(to.otherName(bob)).isEqualTo("Alice");
    }
}
