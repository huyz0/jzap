package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.agent.MutantSwitch;
import io.github.huyz0.jzap.core.mutator.EmptyReturnsMutator;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An EMPTY_RETURNS mutant has to change what the method returns.
 *
 * <p>The mutation replaces a returned reference with the empty value of its type. When the method
 * already returns that value, the mutant is the original program: no test can distinguish it, so
 * it survives every run forever and sits in the report asking a reviewer to write a test for code
 * that is already covered. This is the same rule that stops {@code return ""} being mutated to
 * {@code return ""}, applied to the cases the old guard could not see -- it inspected only
 * constant pushes, and {@code List.of()} is a method call.
 *
 * <p>The expectations here were checked against PIT, by running its EMPTY_RETURNS alone over this
 * same probe: of these nine methods PIT seeds two, {@code mutableEmpty} and {@code nonEmpty}.
 * Matching that is deliberate -- suppressing a mutant PIT keeps would be an inventory difference
 * against the correctness oracle, and keeping one PIT suppresses is the false alarm above.
 */
class EmptyReturnsAreRealTest {

    private static final String SOURCE = """
            package ex;
            import java.util.*;
            public class Empties {
                public List<String> alreadyEmptyList()    { return List.of(); }
                public Set<String> alreadyEmptySet()      { return Set.of(); }
                public Map<String, String> alreadyEmptyMap() { return Map.of(); }
                public Optional<String> alreadyEmptyOpt() { return Optional.empty(); }
                public String alreadyEmptyString()        { return ""; }
                public Boolean alreadyFalse()             { return Boolean.FALSE; }
                public List<String> viaCollections()      { return Collections.emptyList(); }
                public List<String> mutableEmpty()        { return new ArrayList<>(); }
                public List<String> nonEmpty()            { return List.of("a"); }
            }
            """;

    private static List<String> methodsWithEmptyReturnMutants() {
        byte[] bytes = InMemoryJavac.compile("ex.Empties", SOURCE).get("ex.Empties");
        return MutationEngine.withDefaults().discover(":t", bytes).stream()
                .filter(m -> m.key().mutator().equals(EmptyReturnsMutator.ID))
                .map(m -> m.key().methodName())
                .distinct()
                .sorted()
                .toList();
    }

    @Test
    void onlyReturnsThatAreNotAlreadyEmptyAreMutated() {
        assertEquals(List.of("mutableEmpty", "nonEmpty"), methodsWithEmptyReturnMutants(),
                "every other method here already returns the value the mutant would return");
    }

    @Test
    void aMutableEmptyCollectionIsStillWorthMutating() {
        // new ArrayList<>() is empty but modifiable, so replacing it with an immutable empty list
        // is a real change: a caller that adds to the result fails. PIT seeds this one too.
        assertTrue(methodsWithEmptyReturnMutants().contains("mutableEmpty"));
    }

    /**
     * The schemata pass has to decline in exactly the same places.
     *
     * <p>Both passes ask a return mutator whether the mutation would be a no-op, and a mutator
     * that declines also declines to take an ordinal. If the two disagreed, a schemata index would
     * point at a different site than the key it was recorded under -- so activating a mutant would
     * mutate somewhere else, or nothing at all. Checked by activating each seeded EMPTY_RETURNS
     * mutant and requiring the method named in its key to be the one that changes.
     */
    @Test
    void everySeededEmptyReturnMutatesTheMethodItsKeyNames() throws Exception {
        Map<String, byte[]> compiled = InMemoryJavac.compile("ex.Empties", SOURCE);
        byte[] original = compiled.get("ex.Empties");
        List<Mutant> mutants = MutationEngine.withDefaults().discover(":t", original);
        SchemataTransformer.Result result = SchemataTransformer.transform(original, mutants);

        Map<String, byte[]> set = new LinkedHashMap<>(compiled);
        set.put("ex.Empties", result.schemata());
        Class<?> schemata = InMemoryJavac.loader(set).loadClass("ex.Empties");
        Object instance = schemata.getDeclaredConstructor().newInstance();

        int checked = 0;
        for (Map.Entry<MutantKey, Integer> entry : result.indices().entrySet()) {
            if (!entry.getKey().mutator().equals(EmptyReturnsMutator.ID)) {
                continue;
            }
            checked++;
            String method = entry.getKey().methodName();
            String unmutated = describe(schemata.getMethod(method).invoke(instance));
            MutantSwitch.activate(entry.getValue());
            try {
                String mutated = describe(schemata.getMethod(method).invoke(instance));
                assertNotEquals(unmutated, mutated,
                        () -> "activating " + entry.getKey().asString() + " changed nothing in "
                                + method + ", so the index points somewhere else");
            } finally {
                MutantSwitch.deactivate();
            }
        }
        assertEquals(2, checked,
                "mutableEmpty and nonEmpty are the two that are worth mutating");
    }

    /**
     * Class as well as value, because an empty ArrayList and an empty {@code List.of()} both
     * print as {@code []} while differing in the way that matters: one can be added to.
     */
    private static String describe(Object o) {
        return o == null ? "null" : o.getClass().getName() + "(" + o + ")";
    }

    @AfterEach
    void deactivate() {
        MutantSwitch.deactivate();
    }

    /**
     * A return type the mutator does not know is a mutant that never gets generated.
     *
     * <p>Iterable was absent from the table, so a method returning one was never mutated at all.
     * That does not fail or warn: it shrinks the inventory, and the score is a ratio over the
     * inventory. PIT covers Iterable with an empty list, which is what settled it.
     */
    @Test
    void anIterableReturnIsMutated() {
        String source = """
                package ex;
                import java.util.*;
                public class Iterables {
                    public Iterable<String> items() { return new ArrayList<>(); }
                }
                """;
        byte[] bytes = InMemoryJavac.compile("ex.Iterables", source).get("ex.Iterables");

        List<String> mutators = MutationEngine.withDefaults().discover(":t", bytes).stream()
                .map(m -> m.key().mutator())
                .toList();

        assertTrue(mutators.contains(EmptyReturnsMutator.ID),
                () -> "a method returning Iterable produced no empty-return mutant: " + mutators);
    }

    @Test
    void everyMutantDiffersFromTheOriginal() {
        byte[] original = InMemoryJavac.compile("ex.Empties", SOURCE).get("ex.Empties");
        MutationEngine engine = MutationEngine.withDefaults();

        for (Mutant mutant : engine.discover(":t", original)) {
            assertFalse(java.util.Arrays.equals(original, engine.apply(original, mutant.key())),
                    () -> mutant.key().asString() + " compiles to the original's bytecode");
        }
    }
}
