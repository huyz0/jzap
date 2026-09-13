package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.Mutant;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtremeMutationTest {

    private static final String SOURCE = """
            package ex;
            public class Service {
                private int calls;

                public Service() {
                    calls = 0;
                }

                public int compute(int a, int b) {
                    calls++;
                    if (a > b) {
                        return a - b;
                    }
                    return b - a;
                }

                public void record(StringBuilder log) {
                    log.append("called");
                    log.append(calls);
                }

                public String name() {
                    return "service";
                }

                public boolean ready() {
                    return calls > 0;
                }

                public long total(long seed) {
                    long acc = seed;
                    for (int i = 0; i < 3; i++) {
                        acc = acc + i;
                    }
                    return acc;
                }

                public int guarded(String text) {
                    try {
                        return Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            }
            """;

    private static MutationEngine extreme() {
        return new MutationEngine(Mutators.resolve(List.of("EXTREME")));
    }

    private static Map<String, byte[]> compiled() {
        return InMemoryJavac.compile("ex.Service", SOURCE);
    }

    @Test
    void producesOneMutantPerMethodAndNoneForConstructors() {
        List<Mutant> mutants = extreme().discover(":test", compiled().get("ex.Service"));

        Map<String, List<String>> byMethod = new LinkedHashMap<>();
        for (Mutant m : mutants) {
            byMethod.computeIfAbsent(m.key().methodName(), k -> new java.util.ArrayList<>())
                    .add(m.key().mutator());
        }

        assertEquals(Set.of("compute", "record", "name", "ready", "total", "guarded"),
                byMethod.keySet(), "expected one entry per method, got " + byMethod);
        byMethod.forEach((method, mutators) ->
                assertEquals(1, mutators.size(), method + " should have exactly one mutant"));
        assertEquals(List.of("REMOVE_METHOD_BODY"), byMethod.get("record"),
                "a void method gets its body removed");
        assertEquals(List.of("CONSTANT_RETURN"), byMethod.get("compute"),
                "a value-returning method gets a constant return");
        assertFalse(byMethod.containsKey("<init>"),
                "emptying a constructor skips the super call and fails verification");
    }

    @Test
    void isDramaticallySmallerThanTheDefaultSet() {
        byte[] bytes = compiled().get("ex.Service");

        int defaults = MutationEngine.withDefaults().discover(":test", bytes).size();
        int extreme = extreme().discover(":test", bytes).size();

        assertTrue(extreme * 2 < defaults,
                "extreme mutation should be far smaller: " + extreme + " vs " + defaults);
    }

    @Test
    void everyExtremeMutantVerifiesAndRuns() throws Exception {
        Map<String, byte[]> compiled = compiled();
        byte[] original = compiled.get("ex.Service");
        MutationEngine engine = extreme();

        for (Mutant mutant : engine.discover(":test", original)) {
            byte[] mutated = engine.apply(original, mutant.key());

            StringWriter problems = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(mutated), false, new PrintWriter(problems));
            assertTrue(problems.toString().isEmpty(),
                    () -> mutant.key().asString() + ":\n" + problems);

            Map<String, byte[]> set = new LinkedHashMap<>(compiled);
            set.put("ex.Service", mutated);
            Class<?> c = InMemoryJavac.loader(set).loadClass("ex.Service");
            Object instance = c.getDeclaredConstructor().newInstance();
            c.getMethod("compute", int.class, int.class).invoke(instance, 5, 3);
            c.getMethod("record", StringBuilder.class).invoke(instance, new StringBuilder());
            c.getMethod("name").invoke(instance);
            c.getMethod("ready").invoke(instance);
            c.getMethod("total", long.class).invoke(instance, 1L);
            c.getMethod("guarded", String.class).invoke(instance, "7");
        }
    }

    /** Each mutant replaces exactly one method, so each is applied and checked on its own. */
    @Test
    void theReplacedBodyReturnsTheDefaultValue() throws Exception {
        assertNull(invokeWithBodyRemoved("name"),
                "a reference-returning method should come back null");
        assertEquals(0, invokeWithBodyRemoved("compute"));
        assertEquals(false, invokeWithBodyRemoved("ready"));
        assertEquals(0L, invokeWithBodyRemoved("total"));
    }

    /** Applies the extreme mutant for one method, then calls that method. */
    private Object invokeWithBodyRemoved(String methodName) throws Exception {
        Map<String, byte[]> compiled = compiled();
        byte[] original = compiled.get("ex.Service");
        MutationEngine engine = extreme();

        Mutant mutant = engine.discover(":test", original).stream()
                .filter(m -> m.key().methodName().equals(methodName))
                .findFirst().orElseThrow();

        Map<String, byte[]> set = new LinkedHashMap<>(compiled);
        set.put("ex.Service", engine.apply(original, mutant.key()));
        Class<?> c = InMemoryJavac.loader(set).loadClass("ex.Service");
        Object instance = c.getDeclaredConstructor().newInstance();

        return switch (methodName) {
            case "name" -> c.getMethod("name").invoke(instance);
            case "compute" -> c.getMethod("compute", int.class, int.class).invoke(instance, 9, 1);
            case "ready" -> c.getMethod("ready").invoke(instance);
            case "total" -> c.getMethod("total", long.class).invoke(instance, 42L);
            default -> throw new IllegalArgumentException(methodName);
        };
    }

    @Test
    void removingABodyWithATryCatchStillVerifies() throws Exception {
        // The try/catch blocks and local variable table have to go with the body. ASM reports
        // them before the instructions, which is why this mutator buffers the whole method.
        Map<String, byte[]> compiled = compiled();
        byte[] original = compiled.get("ex.Service");
        MutationEngine engine = extreme();

        Mutant guarded = engine.discover(":test", original).stream()
                .filter(m -> m.key().methodName().equals("guarded"))
                .findFirst().orElseThrow();

        byte[] mutated = engine.apply(original, guarded.key());
        StringWriter problems = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(mutated), false, new PrintWriter(problems));
        assertTrue(problems.toString().isEmpty(), problems.toString());

        Map<String, byte[]> set = new LinkedHashMap<>(compiled);
        set.put("ex.Service", mutated);
        Class<?> c = InMemoryJavac.loader(set).loadClass("ex.Service");
        assertEquals(0, c.getMethod("guarded", String.class)
                .invoke(c.getDeclaredConstructor().newInstance(), "not a number"));
    }

    @Test
    void discoveryIsDeterministic() {
        byte[] bytes = compiled().get("ex.Service");
        MutationEngine engine = extreme();

        assertEquals(engine.discover(":t", bytes).stream().map(m -> m.key().asString()).toList(),
                engine.discover(":t", bytes).stream().map(m -> m.key().asString()).toList());
    }

    @Test
    void mutantsPointAtTheMethodsFirstLine() {
        List<Mutant> mutants = extreme().discover(":test", compiled().get("ex.Service"));

        assertTrue(mutants.stream().allMatch(m -> m.key().line() > 0),
                "a mutant with no line cannot be pointed at any source: "
                        + mutants.stream().map(m -> m.key().asString()).collect(Collectors.toList()));
    }
}
