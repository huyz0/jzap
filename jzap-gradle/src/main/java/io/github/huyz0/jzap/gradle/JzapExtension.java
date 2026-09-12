package io.github.huyz0.jzap.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Build-script configuration for jzap.
 *
 * <p>Everything here describes <em>what to analyse</em> and <em>how to report</em>. Nothing
 * here describes how analysis works: that belongs to the engine, and keeping the line clear is
 * what stops this plugin from slowly accumulating a second copy of the tool.
 */
public abstract class JzapExtension {

    /** Engine version to run. Defaults to the plugin's own version. */
    public abstract Property<String> getEngineVersion();

    /**
     * Explicit engine classpath, overriding {@link #getEngineVersion()}.
     *
     * <p>Set this to run a locally built engine, which is also how this plugin's own tests work:
     *
     * <pre>{@code
     * jzap {
     *     engineClasspath.setFrom(fileTree("path/to/jzap/lib") { include("*.jar") })
     * }
     * }</pre>
     *
     * <p>This is the answer when the engine cannot be resolved from a repository. The failure in
     * that case comes from Gradle's dependency resolution rather than from jzap; see
     * {@link JzapTask#getEngineClasspath()} for why it is left that way.
     */
    public abstract ConfigurableFileCollection getEngineClasspath();

    /** Analysis threads. Defaults to one per available processor. */
    public abstract Property<Integer> getThreads();

    /** Reporter ids: console, json, elements, html, annotations. */
    public abstract ListProperty<String> getReporters();

    /** Base git ref for diff scoping. Leaving this unset analyses everything. */
    public abstract Property<String> getFrom();

    /** Tip git ref for diff scoping. {@code -Local-} means uncommitted changes. */
    public abstract Property<String> getTo();

    /** {@code line} to mutate only changed lines, {@code class} to widen to changed classes. */
    public abstract Property<String> getScope();

    /** Class-name globs to mutate. Empty means everything in the module's output. */
    public abstract ListProperty<String> getIncludeClasses();

    /** Class-name globs never to mutate. */
    public abstract ListProperty<String> getExcludeClasses();

    /** Mutator ids. Empty means the default set. */
    public abstract ListProperty<String> getMutators();

    /** Also mutate loop counters, which are suppressed by default. */
    public abstract Property<Boolean> getMutateLoopCounters();

    /** Fail the build if any mutant survives. */
    public abstract Property<Boolean> getFailOnSurvivors();

    /** Extra JVM arguments for the analysis JVMs that run the tests. */
    public abstract ListProperty<String> getJvmArgs();

    private Double threshold;

    /** Fail the build if the mutation score falls below this percentage. */
    public Double getThreshold() {
        return threshold;
    }

    /**
     * A plain scalar rather than a {@code Property<Double>}, and typed as {@link Number}.
     *
     * <p>In a Groovy build script {@code threshold = 80.0} produces a
     * {@link java.math.BigDecimal}, which Gradle will not assign to a {@code Property<Double>};
     * and Gradle forbids declaring a setter next to an abstract property getter, so the two
     * cannot be combined. Without this, the obvious way to write the obvious thing fails with a
     * type error.
     */
    public void setThreshold(Number value) {
        this.threshold = value == null ? null : value.doubleValue();
    }
}
