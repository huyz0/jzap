package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationEngineTest {

    private static final String CALC_SOURCE = """
            package ex;
            public class Calc {
                public int add(int a, int b) {
                    return a + b;
                }
                public boolean isPositive(int a) {
                    return a > 0;
                }
                public int countTo(int n) {
                    int total = 0;
                    for (int i = 0; i < n; i++) {
                        total = total + i;
                    }
                    return total;
                }
                public String label() {
                    return "label";
                }
                public void reset(StringBuilder sb) {
                    sb.setLength(0);
                }
                public int negate(int a) {
                    return -a;
                }
            }
            """;

    private static Map<String, byte[]> calc() {
        return InMemoryJavac.compile("ex.Calc", CALC_SOURCE);
    }

    @Test
    void discoversTheExpectedMutatorsForEachConstruct() {
        byte[] bytes = calc().get("ex.Calc");
        List<Mutant> mutants = MutationEngine.withDefaults().discover(":test", bytes);

        Map<String, Set<String>> byMethod = new LinkedHashMap<>();
        for (Mutant m : mutants) {
            byMethod.computeIfAbsent(m.key().methodName(), k -> new TreeSet<>()).add(m.key().mutator());
        }

        // Hand-written expectations. These are asserted against the source above, not copied
        // from any tool's output, so they can arbitrate when jzap and PIT disagree.
        assertEquals(Set.of("MATH", "PRIMITIVE_RETURNS"), byMethod.get("add"));
        assertEquals(Set.of("CONDITIONALS_BOUNDARY", "NEGATE_CONDITIONALS", "TRUE_RETURNS", "FALSE_RETURNS"),
                byMethod.get("isPositive"));
        assertEquals(Set.of("CONDITIONALS_BOUNDARY", "NEGATE_CONDITIONALS", "INCREMENTS", "MATH",
                "PRIMITIVE_RETURNS"), byMethod.get("countTo"));
        assertEquals(Set.of("EMPTY_RETURNS"), byMethod.get("label"));
        assertEquals(Set.of("VOID_METHOD_CALLS"), byMethod.get("reset"));
        assertEquals(Set.of("INVERT_NEGS", "PRIMITIVE_RETURNS"), byMethod.get("negate"));
    }

    @Test
    void discoveryIsDeterministic() {
        byte[] bytes = calc().get("ex.Calc");
        List<Mutant> first = MutationEngine.withDefaults().discover(":test", bytes);
        List<Mutant> second = MutationEngine.withDefaults().discover(":test", bytes);

        assertEquals(first.stream().map(m -> m.key().asString()).toList(),
                second.stream().map(m -> m.key().asString()).toList());
    }

    @Test
    void everyMutantProducesVerifiableBytecode() {
        byte[] original = calc().get("ex.Calc");
        MutationEngine engine = MutationEngine.withDefaults();
        List<Mutant> mutants = engine.discover(":test", original);
        assertFalse(mutants.isEmpty());

        for (Mutant m : mutants) {
            byte[] mutated = engine.apply(original, m.key());
            assertNotEquals(0, mutated.length);
            StringWriter problems = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(mutated), false, new PrintWriter(problems));
            assertTrue(problems.toString().isEmpty(),
                    () -> "bytecode verification failed for " + m.key().asString() + ":\n" + problems);
        }
    }

    @Test
    void everyMutantLoadsAndRuns() throws Exception {
        Map<String, byte[]> compiled = calc();
        byte[] original = compiled.get("ex.Calc");
        MutationEngine engine = MutationEngine.withDefaults();

        for (Mutant m : engine.discover(":test", original)) {
            Map<String, byte[]> mutatedSet = new LinkedHashMap<>(compiled);
            mutatedSet.put("ex.Calc", engine.apply(original, m.key()));
            Class<?> c = InMemoryJavac.loader(mutatedSet).loadClass("ex.Calc");
            Object instance = c.getDeclaredConstructor().newInstance();
            // Touch every method so verification and linkage errors surface here rather than
            // as a NON_VIABLE verdict during a real run.
            c.getMethod("add", int.class, int.class).invoke(instance, 2, 3);
            c.getMethod("isPositive", int.class).invoke(instance, 1);
            c.getMethod("countTo", int.class).invoke(instance, 3);
            c.getMethod("label").invoke(instance);
            c.getMethod("reset", StringBuilder.class).invoke(instance, new StringBuilder("seed"));
            c.getMethod("negate", int.class).invoke(instance, 5);
        }
    }

    @Test
    void mathMutantChangesBehaviour() throws Exception {
        Map<String, byte[]> compiled = calc();
        byte[] original = compiled.get("ex.Calc");
        MutationEngine engine = MutationEngine.withDefaults();
        MutantKey mathMutant = engine.discover(":test", original).stream()
                .filter(m -> m.key().mutator().equals("MATH") && m.key().methodName().equals("add"))
                .findFirst().orElseThrow().key();

        Map<String, byte[]> mutatedSet = new LinkedHashMap<>(compiled);
        mutatedSet.put("ex.Calc", engine.apply(original, mathMutant));
        Class<?> c = InMemoryJavac.loader(mutatedSet).loadClass("ex.Calc");
        Object instance = c.getDeclaredConstructor().newInstance();

        assertEquals(-1, c.getMethod("add", int.class, int.class).invoke(instance, 2, 3),
                "addition should have become subtraction");
    }

    @Test
    void voidMethodCallMutantSkipsTheCall() throws Exception {
        Map<String, byte[]> compiled = calc();
        byte[] original = compiled.get("ex.Calc");
        MutationEngine engine = MutationEngine.withDefaults();
        MutantKey key = engine.discover(":test", original).stream()
                .filter(m -> m.key().mutator().equals("VOID_METHOD_CALLS"))
                .findFirst().orElseThrow().key();

        Map<String, byte[]> mutatedSet = new LinkedHashMap<>(compiled);
        mutatedSet.put("ex.Calc", engine.apply(original, key));
        Class<?> c = InMemoryJavac.loader(mutatedSet).loadClass("ex.Calc");
        Object instance = c.getDeclaredConstructor().newInstance();
        StringBuilder sb = new StringBuilder("abc");
        c.getMethod("reset", StringBuilder.class).invoke(instance, sb);

        assertEquals("abc", sb.toString(),
                "setLength(0) should have been removed, leaving the buffer untouched");
    }

    @Test
    void applyingAStaleKeyFailsLoudly() {
        byte[] original = calc().get("ex.Calc");
        MutationEngine engine = MutationEngine.withDefaults();
        MutantKey bogus = new MutantKey("ex.Calc", "add", "(II)I", 9999, "MATH", 0);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> engine.apply(original, bogus));
        assertTrue(e.getMessage().contains("recompiled"), e.getMessage());
    }

    @Test
    void mutantKeysAreUniqueWithinAClass() {
        byte[] original = calc().get("ex.Calc");
        List<Mutant> mutants = MutationEngine.withDefaults().discover(":test", original);
        List<String> keys = mutants.stream().map(m -> m.key().asString()).toList();
        assertEquals(keys.size(), keys.stream().collect(Collectors.toSet()).size(),
                "duplicate mutant keys: " + keys);
    }
}
