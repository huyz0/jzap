package io.github.huyz0.jzap.cli;

import io.github.huyz0.jzap.core.MutantFilters;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.ScopeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commands other than {@code run}, and the option handling they share.
 *
 * <p>{@code list-mutants} is the one the parity harness reads, so its output shape is part of
 * jzap's contract with the comparison against PIT rather than a convenience.
 */
class CommandSurfaceTest {

    private record Invocation(int exitCode, String out, String err) {
        String all() {
            return out + err;
        }
    }

    private static Invocation run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outWas = System.out;
        PrintStream errWas = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new JzapCommand()).execute(args);
            return new Invocation(code,
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(outWas);
            System.setErr(errWas);
        }
    }

    // ------------------------------------------------------------ the root command

    @Test
    void noSubcommandIsAUsageError() {
        Invocation result = run();
        assertNotEquals(RunCommand.EXIT_OK, result.exitCode(),
                "running jzap with no command must not look like success");
        assertTrue(result.all().contains("subcommand") || result.all().contains("COMMAND"),
                result.all());
    }

    @Test
    void helpListsEverySubcommand() {
        Invocation result = run("--help");
        assertEquals(RunCommand.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("run"), result.out());
        assertTrue(result.out().contains("list-mutants"), result.out());
        assertTrue(result.out().contains("mutators"), result.out());
        assertTrue(result.out().contains("daemon"), result.out());
    }

    @Test
    void versionIsReported() {
        Invocation result = run("--version");
        assertEquals(RunCommand.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("jzap"), result.out());
    }

    // ------------------------------------------------------------ mutators

    @Test
    void mutatorsListsTheDefaultSetAndSaysItMatchesPit() {
        Invocation result = run("mutators");

        assertEquals(RunCommand.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("PIT's DEFAULTS"),
                "the ids exist to be comparable with PIT, and the output should say so: "
                        + result.out());
        assertTrue(result.out().contains("MATH"), result.out());
        assertTrue(result.out().contains("NEGATE_CONDITIONALS"), result.out());
    }

    // ------------------------------------------------------------ list-mutants

    @Test
    void listMutantsPrintsOneStableKeyPerLine(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);

        Invocation result = run("list-mutants", "-m", model.toString());

        assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
        String[] lines = result.out().strip().split("\n");
        assertTrue(lines.length > 5, "the sample fixture has mutants: " + result.all());
        for (String line : lines) {
            assertTrue(line.contains("::"),
                    "every line should be a mutant key, which is ::-delimited: " + line);
        }
        assertTrue(result.err().contains("mutants in scope"),
                "the count goes to stderr so stdout stays machine-readable: " + result.err());
    }

    @Test
    void listMutantsRunsNoTests(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);

        Invocation result = run("list-mutants", "-m", model.toString());

        assertFalse(result.all().contains("coverage"),
                "the inventory is meant to be cheap: no test may be run. " + result.all());
    }

    @Test
    void listMutantsTableFormatAddsTheMutatorAndDescription(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);

        Invocation result = run("list-mutants", "-m", model.toString(), "--format", "table");

        assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
        assertTrue(result.out().contains("NEGATE_CONDITIONALS")
                        || result.out().contains("MATH"),
                "the table names the mutator: " + result.out());
        assertTrue(result.out().contains("replaced") || result.out().contains("negated"),
                "and describes the change in source terms: " + result.out());
    }

    @Test
    void listMutantsReportsWhatTheFiltersDropped(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);

        Invocation plain = run("list-mutants", "-m", model.toString());
        Invocation reduced = run("list-mutants", "-m", model.toString(), "--one-per-line");

        assertTrue(reduced.err().contains("filters dropped"),
                "asking for a filter should report what it cost: " + reduced.err());
        assertTrue(reduced.err().contains("beyond one per line"), reduced.err());
        assertTrue(reduced.out().strip().split("\n").length
                        < plain.out().strip().split("\n").length,
                "one per line has to actually drop some");
    }

    @Test
    void listMutantsRejectsAMissingModel(@TempDir Path dir) {
        Invocation result = run("list-mutants", "-m", dir.resolve("absent.json").toString());

        assertEquals(RunCommand.EXIT_USAGE, result.exitCode());
        assertTrue(result.err().contains("no project model at"), result.err());
    }

    // ------------------------------------------------------------ daemon

    @Test
    void daemonStatusReportsNoDaemonForAFreshModel(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);

        Invocation result = run("daemon", "-m", model.toString(), "--status");

        assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
        assertTrue(result.out().contains("no daemon running"), result.out());
    }

    @Test
    void stoppingADaemonThatIsNotRunningIsNotAnError(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);

        Invocation result = run("daemon", "-m", model.toString(), "--stop");

        assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
        assertTrue(result.out().contains("no daemon was running"), result.out());
    }

    @Test
    void startingADaemonForAMissingModelIsAUsageError(@TempDir Path dir) {
        Invocation result = run("daemon", "-m", dir.resolve("absent.json").toString());

        assertEquals(RunCommand.EXIT_USAGE, result.exitCode());
        assertTrue(result.err().contains("no project model at"), result.err());
    }

    // ------------------------------------------------------------ shared options

    @Test
    void scopeFlagsOverrideWhatTheModelAsksFor(@TempDir Path dir) {
        ModelOptions options = new ModelOptions();
        options.modelFile = new CliFixture().writeModel(dir);

        assertEquals(ScopeKind.ALL, options.read().scope().kind(),
                "the fixture model asks for ALL");

        options.from = "HEAD~1";
        assertEquals(ScopeKind.DIFF, options.read().scope().kind(),
                "--from selects diff scoping without being told the kind");

        options.from = null;
        options.patchFile = dir.resolve("some.patch");
        assertEquals(ScopeKind.PATCH, options.read().scope().kind(),
                "--patch selects patch scoping");

        options.all = true;
        assertEquals(ScopeKind.ALL, options.read().scope().kind(),
                "--all wins over the others, because it is the explicit override");
    }

    @Test
    void filterFlagsTurnFiltersOnAndOff(@TempDir Path dir) {
        ModelOptions options = new ModelOptions();
        options.modelFile = new CliFixture().writeModel(dir);

        MutantFilters byDefault = MutantFilters.from(options.read().scope());
        assertTrue(byDefault.loopCounters(), "loop counters are filtered unless asked otherwise");
        assertTrue(byDefault.kotlinJunk());
        assertFalse(byDefault.equivalence());
        assertFalse(byDefault.arid());
        assertFalse(byDefault.onePerLine());

        options.mutateLoopCounters = true;
        options.mutateKotlinInternals = true;
        options.dedup = true;
        options.arid = true;
        options.onePerLine = true;

        MutantFilters asked = MutantFilters.from(options.read().scope());
        assertFalse(asked.loopCounters(), "--mutate-loop-counters switches the filter off");
        assertFalse(asked.kotlinJunk());
        assertTrue(asked.equivalence());
        assertTrue(asked.arid());
        assertTrue(asked.onePerLine());
    }

    @Test
    void classAndMutatorSelectionReachTheScope(@TempDir Path dir) {
        ModelOptions options = new ModelOptions();
        options.modelFile = new CliFixture().writeModel(dir);
        options.include = java.util.List.of("sample.*");
        options.exclude = java.util.List.of("sample.Generated*");
        options.mutators = java.util.List.of("MATH");
        options.granularity = "class";

        ProjectModel model = options.read();

        assertEquals(java.util.List.of("sample.*"), model.scope().includeClasses());
        assertEquals(java.util.List.of("sample.Generated*"), model.scope().excludeClasses());
        assertEquals(java.util.List.of("MATH"), model.scope().mutators());
        assertTrue(model.scope().isClassGranularity());
    }

    @Test
    void aPatchScopeNeedsItsFile(@TempDir Path dir) {
        ModelOptions options = new ModelOptions();
        options.modelFile = new CliFixture().writeModel(dir);
        ProjectModel model = options.read();

        // A PATCH scope whose file is absent cannot resolve, and says which option supplies it.
        ProjectModel withoutFile = model.withScope(new io.github.huyz0.jzap.model.Scope(
                ScopeKind.PATCH, null, null, "line", null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of()));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScopeResolution.resolve(withoutFile, dir));
        assertTrue(e.getMessage().contains("--patch"), e.getMessage());
    }

    @Test
    void anAllScopeResolvesToNoLineRestriction(@TempDir Path dir) {
        ModelOptions options = new ModelOptions();
        options.modelFile = new CliFixture().writeModel(dir);

        assertNull(ScopeResolution.resolve(options.read(), dir),
                "null means everything is in scope, which is what the engine expects");
    }

    // ------------------------------------------------------------ scoped inventories

    @Test
    void listMutantsCanBeScopedToAPatch(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);
        Invocation everything = run("list-mutants", "-m", model.toString());
        int all = everything.out().strip().split("\n").length;

        // A patch touching one line of one fixture file. The inventory has to narrow to it
        // without running anything, which is what makes a pull-request check cheap.
        Path patch = CliFixture.write(dir.resolve("one-line.patch"), """
                diff --git a/sample/Discount.java b/sample/Discount.java
                index 1111111..2222222 100644
                --- a/sample/Discount.java
                +++ b/sample/Discount.java
                @@ -17,1 +17,1 @@
                -    old line
                +    new line
                """);

        Invocation scoped = run("list-mutants", "-m", model.toString(),
                "--patch", patch.toString());

        assertEquals(RunCommand.EXIT_OK, scoped.exitCode(), scoped.all());
        int narrowed = scoped.out().strip().isEmpty()
                ? 0
                : scoped.out().strip().split("\n").length;
        assertTrue(narrowed < all,
                "patch scoping has to narrow the inventory: " + narrowed + " of " + all);
        assertTrue(scoped.err().contains("mutants in scope"), scoped.err());
    }

    @Test
    void listMutantsWithAPatchThatTouchesNothingFindsNoMutants(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);
        Path patch = CliFixture.write(dir.resolve("elsewhere.patch"), """
                diff --git a/nothing/Absent.java b/nothing/Absent.java
                index 1111111..2222222 100644
                --- a/nothing/Absent.java
                +++ b/nothing/Absent.java
                @@ -1,1 +1,1 @@
                -    old
                +    new
                """);

        Invocation result = run("list-mutants", "-m", model.toString(),
                "--patch", patch.toString());

        assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
        assertTrue(result.out().strip().isEmpty(), result.out());
        assertTrue(result.err().contains("0 mutants in scope"),
                "an empty inventory is the healthy case for most pull requests: " + result.err());
    }

    @Test
    void listMutantsAtClassGranularityWidensToTheWholeFile(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);
        Path patch = CliFixture.write(dir.resolve("one-line.patch"), """
                diff --git a/sample/Discount.java b/sample/Discount.java
                index 1111111..2222222 100644
                --- a/sample/Discount.java
                +++ b/sample/Discount.java
                @@ -17,1 +17,1 @@
                -    old line
                +    new line
                """);

        Invocation byLine = run("list-mutants", "-m", model.toString(),
                "--patch", patch.toString());
        Invocation byClass = run("list-mutants", "-m", model.toString(),
                "--patch", patch.toString(), "--scope", "class");

        assertEquals(RunCommand.EXIT_OK, byClass.exitCode(), byClass.all());
        assertTrue(byClass.out().strip().split("\n").length
                        >= byLine.out().strip().split("\n").length,
                "class granularity can only widen what line granularity selected");
    }
}
