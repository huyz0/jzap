package io.github.huyz0.jzap.agent;

/**
 * A class whose bytecode {@link ClassOverridesTest} replaces at run time.
 *
 * <p>Its name is exactly as long as {@link OverrideVictimB}'s on purpose; see that test.
 */
final class OverrideVictimA {

    static int value() {
        return 1;
    }
}
