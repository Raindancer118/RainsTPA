package de.raindancer.tpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The promise this module is built on: <em>every feature is reachable by command and by menu,
 * equally</em>.
 *
 * <h2>Why this is a test and not a habit</h2>
 * A menu and a set of commands written separately drift the first time somebody adds something in a
 * hurry — a button with no command is a feature an admin cannot script, and a command with no button
 * is a feature most players never find. Both front ends are generated from {@link TpaFeature}, and
 * these hold that generation honest: every constant names commands that are actually registered,
 * every registered command belongs to a constant, and no two buttons want the same slot.
 *
 * <p>{@code TpaCommands.all(null)} is deliberate: building the list only constructs records and
 * anonymous {@code BasicCommand}s that capture the plugin without touching it, so the whole registry
 * can be read without a server.
 */
class FeatureParityTest {

    private static List<String> registeredCommands() {
        List<String> names = new ArrayList<>();
        for (TpaCommands.Registration registration : TpaCommands.all(null)) {
            names.add(registration.name());
        }
        return names;
    }

    @Test
    @DisplayName("every feature's commands are actually registered")
    void everyFeatureHasItsCommands() {
        List<String> registered = registeredCommands();
        Set<String> missing = new TreeSet<>();
        for (TpaFeature feature : TpaFeature.values()) {
            for (String command : feature.commands()) {
                if (!registered.contains(command)) {
                    missing.add(command);
                }
            }
        }
        assertThat(missing)
                .withFailMessage("These are on a button but cannot be typed: %s", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("every registered command is on a button somewhere")
    void everyCommandHasItsButton() {
        Set<String> onButtons = new HashSet<>();
        for (TpaFeature feature : TpaFeature.values()) {
            onButtons.addAll(feature.commands());
        }
        Set<String> missing = new TreeSet<>(registeredCommands());
        missing.removeAll(onButtons);
        assertThat(missing)
                .withFailMessage("These can be typed but appear on no button, so most players will "
                        + "never find them: %s", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("no two buttons want the same slot, and every slot is on a real page")
    void slotsAreUnique() {
        Set<Integer> seen = new HashSet<>();
        for (TpaFeature feature : TpaFeature.values()) {
            assertThat(seen.add(feature.slot()))
                    .withFailMessage("Two features share slot %s; one of them would never be seen.",
                            feature.slot())
                    .isTrue();
            // Three rows, of which the bottom one is the frame the menu paints itself.
            assertThat(feature.slot()).as("slot of %s", feature.id()).isBetween(0, 17);
        }
    }

    @Test
    @DisplayName("every feature has an id, a title, an icon, a summary and at least one command")
    void everyFeatureIsComplete() {
        Set<String> ids = new HashSet<>();
        for (TpaFeature feature : TpaFeature.values()) {
            assertThat(ids.add(feature.id())).withFailMessage("Duplicate id %s", feature.id()).isTrue();
            assertThat(feature.id()).as("id of %s", feature).matches("[a-z][a-z0-9-]*");
            assertThat(feature.title()).as("title of %s", feature.id()).isNotBlank();
            assertThat(feature.icon()).as("icon of %s", feature.id()).isNotNull();
            assertThat(feature.summary()).as("summary of %s", feature.id()).isNotBlank();
            assertThat(feature.commands()).as("commands of %s", feature.id()).isNotEmpty();
            assertThat(feature.commandList()).as("command list of %s", feature.id()).startsWith("/");
        }
    }

    @Test
    @DisplayName("no command is registered twice, and no alias collides with a command")
    void namesDoNotCollide() {
        Set<String> taken = new HashSet<>();
        for (TpaCommands.Registration registration : TpaCommands.all(null)) {
            assertThat(taken.add(registration.name()))
                    .withFailMessage("%s is registered twice", registration.name()).isTrue();
            assertThat(registration.description())
                    .as("description of /%s", registration.name()).isNotBlank();
        }
        for (TpaCommands.Registration registration : TpaCommands.all(null)) {
            for (String alias : registration.aliases()) {
                assertThat(taken.add(alias))
                        .withFailMessage("The alias %s of /%s is already a command or another alias",
                                alias, registration.name())
                        .isTrue();
            }
        }
    }

    /**
     * {@code /back} is the one feature a server may switch off, and switching it off has to take both
     * front ends with it — a button that answers "that is disabled" is worse than no button.
     */
    @Test
    @DisplayName("only /back can be switched off, and it disappears from the menu when it is")
    void backIsTheOnlyOptionalOne() {
        TpaOptions without = new TpaOptions(60, 3, true, true, 5, true, false, false, true, 10, true);
        List<TpaFeature> shown = new ArrayList<>();
        for (TpaFeature feature : TpaFeature.values()) {
            if (feature.availableUnder(without)) {
                shown.add(feature);
            }
            assertThat(feature.availableUnder(TpaOptions.defaults()))
                    .as("%s under the defaults", feature.id()).isTrue();
        }
        assertThat(shown).doesNotContain(TpaFeature.BACK)
                .hasSize(TpaFeature.values().length - 1);
    }
}
