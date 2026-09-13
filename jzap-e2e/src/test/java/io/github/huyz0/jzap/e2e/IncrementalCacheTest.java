package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.CacheConfig;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache must change how long a run takes and nothing about its answers.
 *
 * <p>Every test here compares a cached run against an uncached one rather than against a
 * recorded expectation, because the failure mode that matters is a cache that is confidently
 * wrong.
 */
class IncrementalCacheTest {

    private static ProjectModel model(Path cacheDir) {
        return new Fixture().model(Scope.all()).withCache(CacheConfig.at(cacheDir.toString()));
    }

    private static AnalysisResult run(Path cacheDir) {
        return new AnalysisEngine(model(cacheDir), AnalysisEngine.Listener.SILENT).analyse(null);
    }

    private static Map<String, String> verdicts(AnalysisResult result) {
        Map<String, String> verdicts = new LinkedHashMap<>();
        result.mutants().forEach(m -> verdicts.put(m.key().asString(), m.status().name()));
        return verdicts;
    }

    @Test
    void aSecondRunReusesEveryReproducibleVerdict(@TempDir Path cacheDir) {
        AnalysisResult first = run(cacheDir);
        assertEquals(0, first.reusedFromCache(), "nothing to reuse on a cold cache");

        AnalysisResult second = run(cacheDir);

        assertEquals(verdicts(first), verdicts(second), "the cache changed a verdict");
        assertEquals(first.mutants().size(), second.reusedFromCache(),
                "every verdict in this fixture is reproducible, so all of them should be reused");
    }

    @Test
    void reusedRunsRunNoTests(@TempDir Path cacheDir) {
        run(cacheDir);

        AnalysisResult second = run(cacheDir);

        assertTrue(second.mutants().stream().allMatch(m -> m.testsRun() == 0),
                "a fully cached run should not execute a single test");
    }

    @Test
    void theCacheIsHumanReadableAndSorted(@TempDir Path cacheDir) throws Exception {
        run(cacheDir);

        List<String> lines = Files.readAllLines(cacheDir.resolve("jzap-cache.txt"),
                StandardCharsets.UTF_8);

        assertEquals("# jzap cache v1", lines.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("toolchain=")),
                "the toolchain must be recorded, or the cache could be reused under another");
        List<String> body = lines.subList(lines.indexOf("---") + 1, lines.size());
        assertFalse(body.isEmpty());

        // Written in fixed sections, each sorted internally, so a diff of the file shows what
        // actually changed rather than a reshuffle.
        List<String> coverage = body.stream().filter(l -> l.startsWith("coverage\t")).toList();
        List<String> mutants = body.stream().filter(l -> l.startsWith("sample.")).toList();
        assertFalse(coverage.isEmpty(), "no coverage was recorded");
        assertFalse(mutants.isEmpty(), "no mutant verdicts were recorded");
        assertEquals(coverage.stream().sorted().toList(), coverage, "coverage lines must be sorted");
        assertEquals(mutants.stream().sorted().toList(), mutants, "mutant lines must be sorted");
        assertTrue(body.indexOf(coverage.get(0)) < body.indexOf(mutants.get(0)),
                "coverage comes before verdicts");
    }

    @Test
    void changingTheMutatedClassInvalidatesOnlyItsOwnMutants(@TempDir Path cacheDir)
            throws Exception {
        run(cacheDir);
        Path cacheFile = cacheDir.resolve("jzap-cache.txt");
        String original = Files.readString(cacheFile);

        // Rewriting one class's hash simulates that class having been recompiled, without
        // needing to recompile the fixture mid-test.
        String tampered = original.lines()
                .map(line -> line.startsWith("sample.Discount")
                        ? line.replaceFirst("\t([0-9a-f]{16})\t", "\t0000000000000000\t")
                        : line)
                .reduce("", (a, b) -> a + b + "\n");
        Files.writeString(cacheFile, tampered);

        AnalysisResult second = run(cacheDir);

        long discountMutants = second.mutants().stream()
                .filter(m -> m.key().className().equals("sample.Discount"))
                .count();
        long stringsMutants = second.mutants().stream()
                .filter(m -> m.key().className().equals("sample.Strings"))
                .count();
        assertEquals(second.mutants().size() - discountMutants, second.reusedFromCache(),
                "only the changed class's mutants should have been re-analysed");
        assertTrue(stringsMutants > 0, "the untouched class should still be reported");
    }

    @Test
    void aChangedMutatorSetDiscardsTheWholeCache(@TempDir Path cacheDir) {
        run(cacheDir);

        ProjectModel narrowed = model(cacheDir).withScope(new Scope(
                io.github.huyz0.jzap.model.ScopeKind.ALL, null, null, "line", null,
                List.of(), List.of(), List.of("MATH"), List.of(), List.of()));
        AnalysisResult second = new AnalysisEngine(narrowed, AnalysisEngine.Listener.SILENT)
                .analyse(null);

        assertEquals(0, second.reusedFromCache(),
                "a different mutator set means different mutants; nothing may be reused");
        assertTrue(second.mutants().stream().allMatch(m -> m.key().mutator().equals("MATH")));
    }

    @Test
    void aChangedFilterSetDiscardsTheWholeCache(@TempDir Path cacheDir) {
        run(cacheDir);

        // Turning loop counters back on changes the inventory, so a cache that ignored the
        // filter set would be serving verdicts for a different set of mutants.
        ProjectModel unfiltered = model(cacheDir).withScope(new Scope(
                io.github.huyz0.jzap.model.ScopeKind.ALL, null, null, "line", null,
                List.of(), List.of(), List.of(), List.of("LOOP_COUNTER"), List.of()));
        AnalysisResult second = new AnalysisEngine(unfiltered, AnalysisEngine.Listener.SILENT)
                .analyse(null);

        assertEquals(0, second.reusedFromCache());
    }

    @Test
    void aCacheFromAnotherToolchainIsRefused(@TempDir Path cacheDir) throws Exception {
        run(cacheDir);
        Path cacheFile = cacheDir.resolve("jzap-cache.txt");
        Files.writeString(cacheFile, Files.readString(cacheFile)
                .replaceFirst("(?m)^toolchain=.*$", "toolchain=some-other-jdk/1.0/plan9/vax"));

        AnalysisResult second = run(cacheDir);

        assertEquals(0, second.reusedFromCache(),
                "bytecode differs between toolchains, so verdicts from one cannot be trusted "
                        + "under another");
    }

    @Test
    void aCorruptCacheIsDiscardedRatherThanTrusted(@TempDir Path cacheDir) throws Exception {
        run(cacheDir);
        Files.writeString(cacheDir.resolve("jzap-cache.txt"), "this is not a cache file\n");

        AnalysisResult second = run(cacheDir);

        assertEquals(0, second.reusedFromCache());
        assertFalse(second.mutants().isEmpty(), "the run must still complete normally");
    }

    @Test
    void timeoutsAreNeverCached(@TempDir Path cacheDir) throws Exception {
        ProjectModel hang = Fixture.hang().model(Scope.all())
                .withCache(CacheConfig.at(cacheDir.toString()));

        AnalysisResult first = new AnalysisEngine(hang, AnalysisEngine.Listener.SILENT).analyse(null);
        assertEquals(1, first.count(MutantStatus.TIMED_OUT), "expected the hanging mutant");

        String cache = Files.readString(cacheDir.resolve("jzap-cache.txt"));
        assertFalse(cache.contains("TIMED_OUT"),
                "a wall-clock timeout is not reproducible, so storing it would be recording a "
                        + "guess as a result:\n" + cache);

        AnalysisResult second = new AnalysisEngine(hang, AnalysisEngine.Listener.SILENT).analyse(null);
        assertEquals(1, second.count(MutantStatus.TIMED_OUT),
                "the hanging mutant must be re-analysed every time");
    }

    @Test
    void aFullyCachedRunDoesNotGatherCoverageAgain(@TempDir Path cacheDir) {
        AnalysisResult first = run(cacheDir);
        assertTrue(first.timings().get("coverage") > 0, "the first run must gather coverage");

        AnalysisResult second = run(cacheDir);

        // Without caching the coverage map, a fully cached run still executes the entire suite
        // once to rediscover it, which on a real project is the dominant cost.
        assertEquals(first.testsDiscovered(), second.testsDiscovered(),
                "the reused map must report the same number of tests");
        assertEquals(verdicts(first), verdicts(second));
        assertTrue(second.timings().get("coverage") < first.timings().get("coverage"),
                "reusing coverage should be faster than gathering it: "
                        + second.timings() + " vs " + first.timings());
    }

    @Test
    void changingATestClassInvalidatesTheCoverageMap(@TempDir Path cacheDir) throws Exception {
        run(cacheDir);
        Path cacheFile = cacheDir.resolve("jzap-cache.txt");

        // The coverage key covers every class and test class, so any change to any of them
        // forces a fresh map: a new test may reach lines the old map says nothing about.
        Files.writeString(cacheFile, Files.readString(cacheFile)
                .replaceFirst("(?m)^coverage-key\t[0-9a-f]{16}", "coverage-key\t0000000000000000"));

        AnalysisResult second = run(cacheDir);

        assertTrue(second.timings().get("coverage") > 0,
                "coverage must be gathered again when its key no longer matches");
        assertFalse(second.mutants().isEmpty());
    }

    @Test
    void aNarrowerRunsCoverageMapIsNotMistakenForAFullOne(@TempDir Path cacheDir) throws Exception {
        // Record coverage for one class only, by scoping to it.
        ProjectModel narrow = model(cacheDir).withScope(new Scope(
                io.github.huyz0.jzap.model.ScopeKind.ALL, null, null, "line", null,
                List.of("sample.Discount"), List.of(), List.of(), List.of(), List.of()));
        new AnalysisEngine(narrow, AnalysisEngine.Listener.SILENT).analyse(null);

        AnalysisResult full = run(cacheDir);

        // sample.Strings was never instrumented in the narrow run, so its mutants would all
        // have looked uncovered had the partial map been reused.
        assertTrue(full.timings().get("coverage") > 0,
                "a partial coverage map must not satisfy a wider run");
        assertTrue(full.mutants().stream()
                        .anyMatch(m -> m.key().className().equals("sample.Strings")),
                "the wider run should still analyse the other class");
    }
}
