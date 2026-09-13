package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.agent.MutantSwitch;
import io.github.huyz0.jzap.model.Mutant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schemata has exactly one thing to prove: activating mutant <i>n</i> in the compiled-in form must
 * behave identically to seeding mutant <i>n</i> on its own. Everything else it offers is speed, and
 * speed bought with a changed verdict is worthless.
 *
 * <p>So the test runs every mutant both ways and compares the observable behaviour of every method,
 * rather than comparing bytecode or trusting that the encoding is obviously right.
 */
class SchemataTransformerTest {

    private static final String SOURCE = """
            package ex;
            import java.util.List;
            import java.util.Optional;

            public class Mixed {
                public int arithmetic(int a, int b) {
                    return a + b * a - b;
                }

                public int compare(int a, int b) {
                    if (a >= b) {
                        return 1;
                    }
                    return -1;
                }

                public boolean flag(int a) {
                    return a > 0;
                }

                public String text(String value) {
                    if (value == null) {
                        return "none";
                    }
                    return value;
                }

                public long counted(int n) {
                    long total = 0;
                    for (int i = 0; i < n; i++) {
                        total += i;
                    }
                    return total;
                }

                public int negated(int a) {
                    return -a;
                }

                public List<String> items(boolean empty) {
                    return empty ? List.of() : List.of("one");
                }

                public Optional<String> maybe(String value) {
                    return Optional.ofNullable(value);
                }

                public double scaled(double a, double b) {
                    return a * b + a;
                }
            }
            """;

    private static Map<String, byte[]> compiled() {
        return InMemoryJavac.compile("ex.Mixed", SOURCE);
    }

    @AfterEach
    void reset() {
        MutantSwitch.deactivate();
    }

    /** Every observable behaviour of the class, as a list of strings. */
    private static List<String> behaviourOf(Class<?> type) throws Exception {
        Object instance = type.getDeclaredConstructor().newInstance();
        List<String> observed = new ArrayList<>();
        record Call(String name, Class<?>[] types, Object[] args) {
        }
        List<Call> calls = List.of(
                new Call("arithmetic", new Class<?>[]{int.class, int.class}, new Object[]{3, 4}),
                new Call("arithmetic", new Class<?>[]{int.class, int.class}, new Object[]{-2, 5}),
                new Call("compare", new Class<?>[]{int.class, int.class}, new Object[]{5, 5}),
                new Call("compare", new Class<?>[]{int.class, int.class}, new Object[]{4, 5}),
                new Call("flag", new Class<?>[]{int.class}, new Object[]{1}),
                new Call("flag", new Class<?>[]{int.class}, new Object[]{0}),
                new Call("text", new Class<?>[]{String.class}, new Object[]{null}),
                new Call("text", new Class<?>[]{String.class}, new Object[]{"hello"}),
                new Call("counted", new Class<?>[]{int.class}, new Object[]{4}),
                new Call("negated", new Class<?>[]{int.class}, new Object[]{7}),
                new Call("items", new Class<?>[]{boolean.class}, new Object[]{false}),
                new Call("maybe", new Class<?>[]{String.class}, new Object[]{"x"}),
                new Call("scaled", new Class<?>[]{double.class, double.class}, new Object[]{2.0, 3.0}));

        for (Call call : calls) {
            Method method = type.getMethod(call.name(), call.types());
            try {
                observed.add(call.name() + String.valueOf(method.invoke(instance, call.args())));
            } catch (InvocationTargetException e) {
                // A mutant that throws is a behaviour like any other, and has to match too.
                observed.add(call.name() + "!" + e.getCause().getClass().getSimpleName());
            }
        }
        return observed;
    }

    private static Class<?> load(Map<String, byte[]> compiled, byte[] replacement) throws Exception {
        Map<String, byte[]> set = new LinkedHashMap<>(compiled);
        set.put("ex.Mixed", replacement);
        return InMemoryJavac.loader(set).loadClass("ex.Mixed");
    }

    @Test
    void activatingAMutantMatchesSeedingItAlone() throws Exception {
        Map<String, byte[]> compiled = compiled();
        byte[] original = compiled.get("ex.Mixed");
        MutationEngine engine = MutationEngine.withDefaults();
        List<Mutant> mutants = engine.discover(":test", original);
        assertFalse(mutants.isEmpty());

        SchemataTransformer.Result schemata = SchemataTransformer.transform(original, mutants);
        assertFalse(schemata.indices().isEmpty(), "nothing was compiled into the schemata class");
        Class<?> schemataClass = load(compiled, schemata.schemata());

        for (Mutant mutant : mutants) {
            Integer index = schemata.indices().get(mutant.key());
            if (index == null) {
                continue;   // routed to the redefinition path, checked separately
            }
            List<String> expected = behaviourOf(load(compiled, engine.apply(original, mutant.key())));

            MutantSwitch.activate(index);
            List<String> actual = behaviourOf(schemataClass);
            MutantSwitch.deactivate();

            assertEquals(expected, actual,
                    () -> "schemata disagrees with the seeded mutant for " + mutant.key().asString());
        }
    }

    @Test
    void withNoMutantActiveTheClassBehavesAsWritten() throws Exception {
        Map<String, byte[]> compiled = compiled();
        byte[] original = compiled.get("ex.Mixed");
        List<Mutant> mutants = MutationEngine.withDefaults().discover(":test", original);

        SchemataTransformer.Result schemata = SchemataTransformer.transform(original, mutants);

        MutantSwitch.deactivate();
        assertEquals(behaviourOf(load(compiled, original)),
                behaviourOf(load(compiled, schemata.schemata())),
                "an inactive schemata class must be indistinguishable from the original");
    }

    @Test
    void theTransformedClassVerifies() {
        byte[] original = compiled().get("ex.Mixed");
        List<Mutant> mutants = MutationEngine.withDefaults().discover(":test", original);

        SchemataTransformer.Result schemata = SchemataTransformer.transform(original, mutants);

        StringWriter problems = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(schemata.schemata()), false,
                new PrintWriter(problems));
        assertTrue(problems.toString().isEmpty(), problems.toString());
    }

    @Test
    void voidMethodCallsAreRoutedRatherThanDropped() {
        String source = """
                package ex;
                public class Talker {
                    public void reset(StringBuilder sink) {
                        sink.setLength(0);
                    }
                }
                """;
        byte[] original = InMemoryJavac.compile("ex.Talker", source).get("ex.Talker");
        List<Mutant> mutants = MutationEngine.withDefaults().discover(":test", original);
        assertTrue(mutants.stream().anyMatch(m -> m.key().mutator().equals("VOID_METHOD_CALLS")));

        SchemataTransformer.Result schemata = SchemataTransformer.transform(original, mutants);

        // Removing a call cannot be expressed without a branch, so these keep the old path. The
        // failure to guard against is silently losing them, which would look like a smaller
        // inventory rather than a bug.
        assertEquals(mutants.stream().filter(m -> m.key().mutator().equals("VOID_METHOD_CALLS")).toList(),
                schemata.fallbacks());
        assertEquals(mutants.size(),
                schemata.indices().size() + schemata.fallbacks().size(),
                "every mutant must be either compiled in or routed");
    }

    @Test
    void growthPerMutationPointIsSmallEnoughToIgnore() {
        // Spike A from the delivery plan, answered by measurement. The branch-free encoding adds
        // roughly a constant push and an invocation per site, so the 64KB per-method limit needs
        // thousands of mutation points in one method before it becomes a design constraint.
        byte[] original = compiled().get("ex.Mixed");
        List<Mutant> mutants = MutationEngine.withDefaults().discover(":test", original);

        SchemataTransformer.Result schemata = SchemataTransformer.transform(original, mutants);
        int growth = schemata.schemata().length - original.length;
        int perMutant = growth / Math.max(1, schemata.indices().size());

        assertTrue(perMutant < 100,
                "expected modest growth per mutation point, got " + perMutant + " bytes across "
                        + schemata.indices().size() + " mutants");
    }
}
