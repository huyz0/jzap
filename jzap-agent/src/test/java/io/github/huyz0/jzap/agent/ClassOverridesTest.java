package io.github.huyz0.jzap.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Installing and removing mutant bytecode, against a real {@code Instrumentation}.
 *
 * <p>This is the mechanism the whole naive engine rests on: a mutant is a class the JVM is asked
 * to swap in for the original. The module's build attaches the shipped agent jar to this test
 * JVM, so what runs here is the same premain, the same transformer and the same retransformation
 * call that an analysis JVM uses.
 *
 * <h2>Where the replacement bytecode comes from</h2>
 *
 * javac, not a bytecode library -- this module is deliberately dependency-free, so there is no
 * ASM here to build a class with. {@link OverrideVictimB} is compiled normally and its class file
 * is turned into a valid class file for {@link OverrideVictimA} by replacing its name in the
 * bytes. The two names are the same length, so every constant-pool entry keeps its declared
 * length and the file stays well-formed.
 */
class ClassOverridesTest {

    @AfterEach
    void restore() {
        ClassOverrides.removeAll();
    }

    @Test
    void installingBytecodeForALoadedClassTakesEffectImmediately() throws Exception {
        assertEquals(1, OverrideVictimA.value(), "the class is loaded before it is overridden");

        ClassOverrides.install(OverrideVictimA.class.getName(), victimBRenamedToA());

        assertEquals(2, OverrideVictimA.value(),
                "a class already loaded must be retransformed, not merely recorded for next time");
    }

    @Test
    void removingAnOverrideRestoresTheOriginal() throws Exception {
        ClassOverrides.install(OverrideVictimA.class.getName(), victimBRenamedToA());
        assertEquals(2, OverrideVictimA.value());

        ClassOverrides.remove(OverrideVictimA.class.getName());

        assertEquals(1, OverrideVictimA.value(),
                "the next mutant must not inherit the previous one's bytecode");
    }

    @Test
    void removeAllClearsEveryOverride() throws Exception {
        ClassOverrides.install(OverrideVictimA.class.getName(), victimBRenamedToA());
        assertEquals(2, OverrideVictimA.value());

        ClassOverrides.removeAll();

        assertEquals(1, OverrideVictimA.value());
    }

    @Test
    void removingSomethingThatWasNeverInstalledIsHarmless() {
        ClassOverrides.remove("io.github.huyz0.jzap.agent.NotOverridden");
        ClassOverrides.remove(OverrideVictimA.class.getName());
    }

    @Test
    void theOriginalBytesAreKeptAsFirstSeen() throws Exception {
        // Loading it is what makes the transformer record it.
        assertEquals(1, OverrideVictimA.value());
        byte[] original = ClassOverrides.original(OverrideVictimA.class.getName());
        assertNotNull(original, "the transformer records every class it is offered");

        ClassOverrides.install(OverrideVictimA.class.getName(), victimBRenamedToA());

        assertArrayEquals(original, ClassOverrides.original(OverrideVictimA.class.getName()),
                "overriding a class must not overwrite the record of what it originally was");
    }

    @Test
    void aClassThatWasNeverLoadedHasNoRecordedOriginal() {
        assertNull(ClassOverrides.original("io.github.huyz0.jzap.agent.NeverLoaded"));
    }

    @Test
    void installingForAClassNotYetLoadedIsPickedUpWhenItLoads() throws Exception {
        ClassOverrides.install("io.github.huyz0.jzap.agent.NotLoadedYet", new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3},
                ClassOverrides.lookup("io/github/huyz0/jzap/agent/NotLoadedYet"),
                "the load-time transformer serves it, so install must not require the class");
    }

    @Test
    void theAgentIsLoadedAndExposesItsInstrumentation() {
        assertTrue(JzapAgent.isLoaded());
        assertNotNull(JzapAgent.instrumentation());
        assertTrue(JzapAgent.instrumentation().isRetransformClassesSupported(),
                "retransformation is what installing a mutant depends on");
    }

    @Test
    void theTransformerIgnoresAClassWithNoName() {
        // The JVM passes null for classes being defined by certain loaders, and a transformer
        // that dereferenced it would break every one of them.
        assertNull(new OverrideTransformer().transform(
                null, null, null, null, new byte[]{1}));
    }

    @Test
    void theTransformerLeavesUnrelatedClassesAlone() {
        assertNull(new OverrideTransformer().transform(
                getClass().getClassLoader(), "some/other/Class", null, null, new byte[]{1}),
                "returning null means 'use the bytes you already have'");
    }

    /**
     * {@link OverrideVictimB}'s class file, renamed so the JVM will accept it for A.
     *
     * <p>Both names are 15 characters, so this is a straight substitution: no constant-pool
     * length changes and no need to understand the class file format.
     */
    private static byte[] victimBRenamedToA() throws IOException {
        String resource = OverrideVictimB.class.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = ClassOverridesTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "cannot find " + resource + " on the test classpath");
            bytes = in.readAllBytes();
        }
        byte[] from = "OverrideVictimB".getBytes(StandardCharsets.UTF_8);
        byte[] to = "OverrideVictimA".getBytes(StandardCharsets.UTF_8);
        assertEquals(from.length, to.length, "the substitution relies on equal lengths");
        for (int i = 0; i + from.length <= bytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < from.length; j++) {
                if (bytes[i + j] != from[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(to, 0, bytes, i, to.length);
            }
        }
        return bytes;
    }
}
