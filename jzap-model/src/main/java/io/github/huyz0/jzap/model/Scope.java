package io.github.huyz0.jzap.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * What to analyse.
 *
 * <p>Note the semantic that both Mull and arcmutate document as a source of misleading
 * results, and which jzap states in its CLI output: analysis always runs against the
 * <em>current</em> compiled code. A git range only selects which lines are in scope; it
 * never checks anything out.
 *
 * @param kind        selection strategy
 * @param from        git ref for the base of the range; {@code -Empty-} means the empty tree
 * @param to          git ref for the tip; {@code -Local-} means staged + unstaged changes
 * @param granularity {@code line} restricts mutants to changed lines, {@code class} widens
 *                    to every mutant in a changed class
 * @param patchFile   path to a unified diff, for {@link ScopeKind#PATCH}
 * @param includeClasses glob patterns of classes to include; empty means all
 * @param excludeClasses glob patterns of classes to exclude
 * @param mutators    mutator ids to use; empty means the default set
 * @param disabledFilters ids of default-on mutant filters to switch off. Filters suppress
 *                    mutants judged not worth seeding, which is a judgement a user may disagree
 *                    with, so each one can be turned off by name.
 * @param enabledFilters ids of default-off filters to switch on. Separate from
 *                    {@code disabledFilters} because the two answer different questions: one
 *                    overrides a default, the other opts into something jzap will not do unasked.
 */
public record Scope(
        ScopeKind kind,
        String from,
        String to,
        String granularity,
        String patchFile,
        List<String> includeClasses,
        List<String> excludeClasses,
        List<String> mutators,
        List<String> disabledFilters,
        List<String> enabledFilters) {

    public static final String LOCAL = "-Local-";
    public static final String EMPTY_TREE = "-Empty-";

    public Scope {
        kind = kind == null ? ScopeKind.ALL : kind;
        granularity = granularity == null ? "line" : granularity;
        includeClasses = includeClasses == null ? List.of() : List.copyOf(includeClasses);
        excludeClasses = excludeClasses == null ? List.of() : List.copyOf(excludeClasses);
        mutators = mutators == null ? List.of() : List.copyOf(mutators);
        disabledFilters = disabledFilters == null ? List.of() : List.copyOf(disabledFilters);
        enabledFilters = enabledFilters == null ? List.of() : List.copyOf(enabledFilters);
        if (!granularity.equals("line") && !granularity.equals("class")) {
            throw new IllegalArgumentException("granularity must be 'line' or 'class', got: " + granularity);
        }
    }

    public static Scope all() {
        return new Scope(ScopeKind.ALL, null, null, "line", null,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public static Scope diff(String from, String to) {
        return new Scope(ScopeKind.DIFF, from, to, "line", null,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Whether a changed class puts all of its mutants in scope, rather than only changed lines.
     *
     * <p>Not serialised: it is derived from {@link #granularity()}, and Jackson would otherwise
     * write it as a {@code classGranularity} field that {@link ModelIo} then warns about as
     * unknown when it reads its own output back.
     */
    @JsonIgnore
    public boolean isClassGranularity() {
        return "class".equals(granularity);
    }

    /** Whether a filter that is on by default is still on. */
    public boolean isFilterEnabled(String filterId) {
        return !disabledFilters.contains(filterId);
    }

    /** Whether a filter that is off by default has been asked for. */
    public boolean isOptionalFilterEnabled(String filterId) {
        return enabledFilters.contains(filterId);
    }
}
