package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.agent.MutantSwitch;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schemata for every return type, and for loops built out of switches.
 *
 * <p>A return mutant is compiled differently for each width -- {@code longReturn},
 * {@code floatReturn}, {@code doubleReturn}, {@code intReturn} -- because the dispatch method has
 * to take and return the same primitive the method does. Getting one wrong does not produce a
 * wrong verdict; it produces a class that will not verify, and the mutant is then reported as
 * non-viable, which looks like a property of the code under test rather than a bug in jzap.
 *
 * <p>{@link EngineDifferentialTest} compares the schemata engine against the reference engine on
 * every fixture, which is the stronger check. This adds the types no fixture happens to return.
 */
class SchemataReturnTypesTest {

    private static final String SOURCE = """
            package ex;

            public class Widths {

                public long asLong(long v) {
                    return v + 1L;
                }

                public float asFloat(float v) {
                    return v + 1f;
                }

                public double asDouble(double v) {
                    return v + 1d;
                }

                public short asShort(short v) {
                    return (short) (v + 1);
                }

                public byte asByte(byte v) {
                    return (byte) (v + 1);
                }

                public char asChar(char v) {
                    return (char) (v + 1);
                }

                public boolean isPositive(int v) {
                    return v > 0;
                }

                public String describe(int v) {
                    return "value " + v;
                }

                public int[] asArray(int v) {
                    return new int[]{v};
                }

                /** A loop whose back edge is a switch, which is how a state machine compiles. */
                public int stateMachine(int steps) {
                    int state = 0;
                    int visited = 0;
                    while (true) {
                        switch (state) {
                            case 0:
                                state = 1;
                                break;
                            case 1:
                                state = 2;
                                break;
                            case 2:
                                visited++;
                                state = visited < steps ? 0 : 3;
                                break;
                            default:
                                return visited;
                        }
                    }
                }

                /** A sparse switch, which javac compiles to lookupswitch rather than a table. */
                public int sparse(int key) {
                    int total = 0;
                    for (int i = 0; i < 3; i++) {
                        switch (key) {
                            case 1: total += 1; break;
                            case 1000: total += 2; break;
                            case 1000000: total += 3; break;
                            default: total += 0; break;
                        }
                    }
                    return total;
                }
            }
            """;

    @AfterEach
    void reset() {
        MutantSwitch.deactivate();
    }

    private static Map<String, byte[]> compiled() {
        return InMemoryJavac.compile("ex.Widths", SOURCE);
    }

    private static Class<?> load(byte[] replacement) throws Exception {
        Map<String, byte[]> set = new LinkedHashMap<>(compiled());
        set.put("ex.Widths", replacement);
        return InMemoryJavac.loader(set).loadClass("ex.Widths");
    }

    @Test
    void everyReturnTypeCompilesIntoASchemataClassThatVerifies() throws Exception {
        byte[] original = compiled().get("ex.Widths");
        List<Mutant> mutants = MutationEngine.withDefaults().discover(":t", original);
        assertFalse(mutants.isEmpty());

        SchemataTransformer.Result result = SchemataTransformer.transform(original, mutants);

        // Loading it is the verification: a dispatch call with the wrong descriptor fails here.
        Class<?> type = load(result.schemata());
        Object instance = type.getDeclaredConstructor().newInstance();

        assertEquals(2L, type.getMethod("asLong", long.class).invoke(instance, 1L));
        assertEquals(2f, type.getMethod("asFloat", float.class).invoke(instance, 1f));
        assertEquals(2d, type.getMethod("asDouble", double.class).invoke(instance, 1d));
        assertEquals((short) 2, type.getMethod("asShort", short.class).invoke(instance, (short) 1));
        assertEquals((byte) 2, type.getMethod("asByte", byte.class).invoke(instance, (byte) 1));
        assertEquals('b', type.getMethod("asChar", char.class).invoke(instance, 'a'));
        assertEquals(true, type.getMethod("isPositive", int.class).invoke(instance, 1));
        assertEquals("value 1", type.getMethod("describe", int.class).invoke(instance, 1));
    }

    @Test
    void withNoMutantActiveEveryMethodBehavesAsTheOriginalDid() throws Exception {
        byte[] original = compiled().get("ex.Widths");
        List<Mutant> mutants = MutationEngine.withDefaults().discover(":t", original);

        Class<?> plain = load(original);
        Class<?> schemata = load(SchemataTransformer.transform(original, mutants).schemata());

        assertEquals(behaviourOf(plain), behaviourOf(schemata),
                "a schemata class with nothing selected has to be the original program");
    }

    @Test
    void eachSeededMutantChangesSomethingObservable() throws Exception {
        byte[] original = compiled().get("ex.Widths");
        List<Mutant> mutants = MutationEngine.withDefaults().discover(":t", original);
        SchemataTransformer.Result result = SchemataTransformer.transform(original, mutants);
        Class<?> schemata = load(result.schemata());
        List<String> unmutated = behaviourOf(schemata);

        int changed = 0;
        for (Map.Entry<MutantKey, Integer> entry : result.indices().entrySet()) {
            MutantSwitch.activate(entry.getValue());
            try {
                if (!behaviourOf(schemata).equals(unmutated)) {
                    changed++;
                }
            } finally {
                MutantSwitch.deactivate();
            }
        }

        assertTrue(changed > 0, "no seeded mutant altered behaviour, so none of them are real");
    }

    @Test
    void aReturnMutantForEachWidthForcesItsOwnZero() throws Exception {
        byte[] original = compiled().get("ex.Widths");
        List<Mutant> mutants = MutationEngine.withDefaults().discover(":t", original);
        SchemataTransformer.Result result = SchemataTransformer.transform(original, mutants);
        Class<?> schemata = load(result.schemata());
        Object instance = schemata.getDeclaredConstructor().newInstance();

        Map<String, Object> forcedZero = new LinkedHashMap<>();
        for (Map.Entry<MutantKey, Integer> entry : result.indices().entrySet()) {
            MutantKey key = entry.getKey();
            if (!key.mutator().equals("PRIMITIVE_RETURNS")) {
                continue;
            }
            MutantSwitch.activate(entry.getValue());
            try {
                switch (key.methodName()) {
                    case "asLong" -> forcedZero.put("asLong",
                            schemata.getMethod("asLong", long.class).invoke(instance, 5L));
                    case "asFloat" -> forcedZero.put("asFloat",
                            schemata.getMethod("asFloat", float.class).invoke(instance, 5f));
                    case "asDouble" -> forcedZero.put("asDouble",
                            schemata.getMethod("asDouble", double.class).invoke(instance, 5d));
                    default -> { }
                }
            } finally {
                MutantSwitch.deactivate();
            }
        }

        assertEquals(0L, forcedZero.get("asLong"), "a long return has to be forced to 0L");
        assertEquals(0f, forcedZero.get("asFloat"));
        assertEquals(0d, forcedZero.get("asDouble"));
    }

    // ------------------------------------------------------------ switch back edges

    @Test
    void aLoopWhoseBackEdgeIsASwitchIsStillGuarded() throws Exception {
        byte[] original = compiled().get("ex.Widths");
        List<Mutant> mutants = MutationEngine.withDefaults().discover(":t", original);
        Class<?> schemata = load(SchemataTransformer.transform(original, mutants).schemata());
        Object instance = schemata.getDeclaredConstructor().newInstance();

        // Unguarded, a state machine like this is exactly the shape a mutant turns into an
        // infinite loop, and the wall clock would be the only thing that noticed.
        assertEquals(3, schemata.getMethod("stateMachine", int.class).invoke(instance, 3));
        assertEquals(9, schemata.getMethod("sparse", int.class).invoke(instance, 1000000),
                "a lookupswitch back edge has to be guarded too, not only a tableswitch");
    }

    @Test
    void guardedSwitchLoopsBehaveExactlyAsTheOriginalDid() throws Exception {
        byte[] original = compiled().get("ex.Widths");
        List<Mutant> mutants = MutationEngine.withDefaults().discover(":t", original);

        Class<?> plain = load(original);
        Class<?> guarded = load(SchemataTransformer.transform(original, mutants).schemata());

        for (int steps : List.of(1, 2, 5)) {
            assertEquals(
                    plain.getMethod("stateMachine", int.class)
                            .invoke(plain.getDeclaredConstructor().newInstance(), steps),
                    guarded.getMethod("stateMachine", int.class)
                            .invoke(guarded.getDeclaredConstructor().newInstance(), steps),
                    "the guard must count iterations, not change them, at steps=" + steps);
        }
        for (int key : List.of(1, 1000, 1000000, 7)) {
            assertEquals(
                    plain.getMethod("sparse", int.class)
                            .invoke(plain.getDeclaredConstructor().newInstance(), key),
                    guarded.getMethod("sparse", int.class)
                            .invoke(guarded.getDeclaredConstructor().newInstance(), key),
                    "at key=" + key);
        }
    }

    /** Every observable result of the class, for comparing one build against another. */
    private static List<String> behaviourOf(Class<?> type) throws Exception {
        Object instance = type.getDeclaredConstructor().newInstance();
        List<String> observed = new java.util.ArrayList<>();
        record Call(String name, Class<?> parameter, Object argument) {
        }
        List<Call> calls = List.of(
                new Call("asLong", long.class, 3L),
                new Call("asFloat", float.class, 3f),
                new Call("asDouble", double.class, 3d),
                new Call("asShort", short.class, (short) 3),
                new Call("asByte", byte.class, (byte) 3),
                new Call("asChar", char.class, 'c'),
                new Call("isPositive", int.class, -1),
                new Call("isPositive", int.class, 2),
                new Call("describe", int.class, 7),
                new Call("stateMachine", int.class, 2),
                new Call("sparse", int.class, 1000));

        for (Call call : calls) {
            Method method = type.getMethod(call.name(), call.parameter());
            try {
                observed.add(call.name() + "=" + method.invoke(instance, call.argument()));
            } catch (java.lang.reflect.InvocationTargetException e) {
                observed.add(call.name() + "!" + e.getCause().getClass().getSimpleName());
            }
        }
        Object array = type.getMethod("asArray", int.class).invoke(instance, 4);
        observed.add("asArray=" + java.util.Arrays.toString((int[]) array));
        return observed;
    }
}
