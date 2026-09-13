package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.Scope;

import java.util.ArrayList;
import java.util.List;

/**
 * Which mutant filters are in force.
 *
 * <p>The single place that knows the filter set. Every filter has to be named in four different
 * jobs — resolving it from a {@link Scope}, switching it on in {@link MutationEngine}, listing it
 * in the cache header, and offering it as a command-line flag — and while each job kept its own
 * list, adding a filter meant finding all four and forgetting one meant a cache that served
 * verdicts for an inventory it had not been keyed on.
 *
 * <p>Each filter is either on by default and switched off by name, or off by default and switched
 * on by name. {@link Scope} keeps those two as separate lists because they answer different
 * questions: one overrides a default, the other opts into something jzap will not do unasked.
 *
 * @param loopCounters suppress INCREMENTS mutants on loop counters; see {@link LoopCounterFilter}
 * @param kotlinJunk   drop mutants in constructs the Kotlin compiler generated. Inert for classes
 *                     javac produced; see {@link KotlinFilter}
 * @param equivalence  drop mutants whose compiled form matches the original's or another
 *                     mutant's; see {@link EquivalenceFilter}
 * @param arid         drop mutants in code that reports rather than decides; see
 *                     {@link AridFilter}
 * @param onePerLine   keep at most one mutant per source line
 */
public record MutantFilters(
        boolean loopCounters,
        boolean kotlinJunk,
        boolean equivalence,
        boolean arid,
        boolean onePerLine) {

    /**
     * Every filter's id, which is how a user names one to switch it on or off.
     *
     * <p>Exposed from here rather than from the filters themselves. A caller naming a filter --
     * the command line is the only one -- wants the vocabulary, not the bytecode analysis behind
     * it, and reaching past this record for four ID constants made four implementation classes
     * part of this module's public surface for no other reason.
     *
     * <p>Each is the filter's own definition rather than a second copy of the string, so there is
     * still exactly one place a filter is named.
     */
    public static final String LOOP_COUNTERS = LoopCounterFilter.ID;

    /** @see #LOOP_COUNTERS */
    public static final String KOTLIN_JUNK = KotlinFilter.ID;

    /** @see #LOOP_COUNTERS */
    public static final String EQUIVALENCE = EquivalenceFilter.ID;

    /** @see #LOOP_COUNTERS */
    public static final String ARID = AridFilter.ID;

    /**
     * Keeping at most one mutant per source line.
     *
     * <p>The only id with no filter class behind it, because the rule is three lines inside
     * {@link MutationEngine} rather than a bytecode analysis of its own.
     */
    public static final String ONE_PER_LINE = "ONE_PER_LINE";

    /** The defaults: the two filters nobody has to ask for, and none of the three they do. */
    public static MutantFilters defaults() {
        return new MutantFilters(true, true, false, false, false);
    }

    /** What the user's scope asks for, defaults included. */
    public static MutantFilters from(Scope scope) {
        return new MutantFilters(
                scope.isFilterEnabled(LoopCounterFilter.ID),
                scope.isFilterEnabled(KotlinFilter.ID),
                scope.isOptionalFilterEnabled(EquivalenceFilter.ID),
                scope.isOptionalFilterEnabled(AridFilter.ID),
                scope.isOptionalFilterEnabled(ONE_PER_LINE));
    }

    public MutantFilters withLoopCounters(boolean on) {
        return new MutantFilters(on, kotlinJunk, equivalence, arid, onePerLine);
    }

    public MutantFilters withKotlinJunk(boolean on) {
        return new MutantFilters(loopCounters, on, equivalence, arid, onePerLine);
    }

    public MutantFilters withEquivalence(boolean on) {
        return new MutantFilters(loopCounters, kotlinJunk, on, arid, onePerLine);
    }

    public MutantFilters withArid(boolean on) {
        return new MutantFilters(loopCounters, kotlinJunk, equivalence, on, onePerLine);
    }

    public MutantFilters withOnePerLine(boolean on) {
        return new MutantFilters(loopCounters, kotlinJunk, equivalence, arid, on);
    }

    /**
     * The active filter ids, sorted, for the cache header.
     *
     * <p>Load-bearing rather than decorative: every filter changes the inventory, so a cache that
     * ignored the set would serve verdicts for a different set of mutants.
     */
    public String cacheKey() {
        List<String> active = new ArrayList<>(5);
        if (loopCounters) {
            active.add(LoopCounterFilter.ID);
        }
        if (kotlinJunk) {
            active.add(KotlinFilter.ID);
        }
        if (equivalence) {
            active.add(EquivalenceFilter.ID);
        }
        if (arid) {
            active.add(AridFilter.ID);
        }
        if (onePerLine) {
            active.add(ONE_PER_LINE);
        }
        active.sort(String::compareTo);
        return String.join(",", active);
    }
}
