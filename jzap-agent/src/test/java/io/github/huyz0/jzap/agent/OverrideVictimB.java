package io.github.huyz0.jzap.agent;

/**
 * The replacement behaviour for {@link OverrideVictimA}, compiled by javac rather than assembled
 * by hand.
 *
 * <p>Same shape and a name of exactly the same length, which is what lets the test turn its class
 * file into a valid class file for A with a byte-for-byte substitution.
 */
final class OverrideVictimB {

    static int value() {
        return 2;
    }
}
