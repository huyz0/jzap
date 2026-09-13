package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.Scope;
import io.github.huyz0.jzap.model.ScopeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which filters are in force, and the string the cache is keyed on.
 *
 * <p>The cache key is the part that has to be right. Every filter changes which mutants exist, so
 * a cache keyed on a set that does not match the run would serve verdicts for a different
 * inventory -- reporting a mutant as killed when this run would not even have seeded it.
 */
class MutantFiltersTest {

    private static Scope scopeWith(List<String> disabled, List<String> enabled) {
        return new Scope(ScopeKind.ALL, null, null, "line", null,
                List.of(), List.of(), List.of(), disabled, enabled);
    }

    @Test
    void theDefaultsAreTheTwoNobodyHasToAskFor() {
        MutantFilters defaults = MutantFilters.defaults();

        assertTrue(defaults.loopCounters(),
                "negating a loop counter either hangs the test or crashes it immediately");
        assertTrue(defaults.kotlinJunk(), "no developer mistake produces compiler-generated code");
        assertFalse(defaults.equivalence(), "the reduction filters are opt-in");
        assertFalse(defaults.arid());
        assertFalse(defaults.onePerLine());
    }

    @Test
    void anEmptyScopeGivesTheDefaults() {
        assertEquals(MutantFilters.defaults(),
                MutantFilters.from(scopeWith(List.of(), List.of())));
    }

    @Test
    void aDefaultOnFilterIsSwitchedOffByName() {
        MutantFilters filters = MutantFilters.from(
                scopeWith(List.of(LoopCounterFilter.ID), List.of()));

        assertFalse(filters.loopCounters());
        assertTrue(filters.kotlinJunk(), "naming one must not disturb the other");
    }

    @Test
    void aDefaultOffFilterIsSwitchedOnByName() {
        MutantFilters filters = MutantFilters.from(
                scopeWith(List.of(), List.of(AridFilter.ID)));

        assertTrue(filters.arid());
        assertFalse(filters.equivalence());
        assertFalse(filters.onePerLine());
    }

    @Test
    void everyFilterCanBeAskedForAtOnce() {
        MutantFilters filters = MutantFilters.from(scopeWith(
                List.of(LoopCounterFilter.ID, KotlinFilter.ID),
                List.of(EquivalenceFilter.ID, AridFilter.ID, MutantFilters.ONE_PER_LINE)));

        assertFalse(filters.loopCounters());
        assertFalse(filters.kotlinJunk());
        assertTrue(filters.equivalence());
        assertTrue(filters.arid());
        assertTrue(filters.onePerLine());
    }

    @Test
    void eachWitherChangesOnlyItsOwnFilter() {
        MutantFilters base = MutantFilters.defaults();

        assertFalse(base.withLoopCounters(false).loopCounters());
        assertTrue(base.withLoopCounters(false).kotlinJunk());

        assertFalse(base.withKotlinJunk(false).kotlinJunk());
        assertTrue(base.withKotlinJunk(false).loopCounters());

        assertTrue(base.withEquivalence(true).equivalence());
        assertTrue(base.withArid(true).arid());
        assertTrue(base.withOnePerLine(true).onePerLine());

        assertEquals(MutantFilters.defaults(), base, "a wither must not mutate its receiver");
    }

    // ------------------------------------------------------------ the cache key

    @Test
    void theCacheKeyListsWhatIsActuallyInForce() {
        assertEquals(KotlinFilter.ID + "," + LoopCounterFilter.ID,
                MutantFilters.defaults().cacheKey(),
                "the two default-on filters, in sorted order");
    }

    @Test
    void theCacheKeyIsEmptyWhenNothingIsFiltered() {
        assertEquals("", MutantFilters.defaults()
                .withLoopCounters(false).withKotlinJunk(false).cacheKey());
    }

    @Test
    void theCacheKeyChangesWithEveryFilter() {
        MutantFilters base = MutantFilters.defaults();
        String baseKey = base.cacheKey();

        for (MutantFilters changed : List.of(
                base.withLoopCounters(false),
                base.withKotlinJunk(false),
                base.withEquivalence(true),
                base.withArid(true),
                base.withOnePerLine(true))) {
            assertFalse(baseKey.equals(changed.cacheKey()),
                    "a filter that did not change the key would let the cache serve verdicts "
                            + "for an inventory it was never keyed on: " + changed);
        }
    }

    @Test
    void theCacheKeyIsSortedSoTheOrderAskedForCannotChangeIt() {
        MutantFilters oneWay = MutantFilters.from(scopeWith(List.of(),
                List.of(AridFilter.ID, EquivalenceFilter.ID)));
        MutantFilters otherWay = MutantFilters.from(scopeWith(List.of(),
                List.of(EquivalenceFilter.ID, AridFilter.ID)));

        assertEquals(oneWay.cacheKey(), otherWay.cacheKey(),
                "the order flags appear in is not a property of the run");
    }
}
