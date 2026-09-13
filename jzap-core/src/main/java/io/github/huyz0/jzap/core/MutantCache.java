package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;
import io.github.huyz0.jzap.model.MutantStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reuses verdicts from a previous run, when it can be shown that nothing relevant changed.
 *
 * <p>Mutating methods are synchronised because analysis workers record concurrently.
 *
 * <p>Plain text on purpose. arcmutate's history files are readable and PIT's are not, and the
 * difference matters the first time someone has to work out why a verdict was reused.
 *
 * <p>The file is written in fixed sections, each sorted internally: the header, then
 * {@code coverage-key}, {@code coverage}, {@code test-duration} and {@code failing-test} lines,
 * then one line per mutant. Sections rather than one sorted blob because the file is meant to be
 * read, and a diff of it should show what actually changed.
 *
 * <h2>What is keyed</h2>
 *
 * The header covers everything that changes the meaning of every entry at once: engine id and
 * version, the mutator set, the enabled filter set, and the toolchain fingerprint. Any mismatch
 * discards the whole file. The filter set is load-bearing rather than decorative — turning loop
 * counters back on changes the inventory, so a cache that ignored it would serve verdicts for a
 * different set of mutants.
 *
 * <h2>When an entry may be reused</h2>
 *
 * <ul>
 *   <li>The mutated class's bytecode must be unchanged. Otherwise the mutant may not even exist.
 *   <li>A <b>killed</b> mutant is reused when its killing test still covers it and that test's
 *       class is unchanged. Other tests changing is irrelevant: the mutant is still killed by
 *       the one that killed it.
 *   <li>An <b>unkilled</b> mutant is reused only when the covering set is identical and every
 *       covering test's class is unchanged. A new or changed test is exactly the thing that
 *       might kill it.
 *   <li><b>Timeouts and run errors are never stored.</b> A wall-clock timeout is not
 *       reproducible, so reusing one would be reporting a guess as a result.
 * </ul>
 *
 * <h2>Coverage</h2>
 *
 * The per-test coverage map is cached too, keyed on the bytecode of every scanned class and
 * every test class. Without this a fully cached run still executes the entire test suite once
 * to rediscover coverage, which on any real project is the dominant cost and makes the cache
 * close to pointless. It is reused only when that key matches <em>and</em> the stored map covers
 * every class the current run needs, so a map recorded during a narrow diff run is not mistaken
 * for a complete one.
 *
 * <p>Any change to any class invalidates the whole coverage map, which is why re-running after
 * one recompiled class costs several times a no-change run. That is deliberate rather than lazy:
 * a changed production class can alter which lines its callers reach, so invalidating only the
 * changed class's coverage would be unsound.
 */
public final class MutantCache {

    private static final String FORMAT = "jzap cache v2";
    private static final String SEPARATOR = "---";

    /** What a previous run recorded about one mutant. */
    private record Entry(
            MutantStatus status,
            String classHash,
            String killingTest,
            String killingTestHash,
            String coveringFingerprint,
            int coveringTests,
            int testsRun) {
    }

    /** Everything about a run that invalidates every entry at once. */
    public record Header(
            String engine,
            String engineVersion,
            String mutators,
            String filters,
            String toolchain) {

        private List<String> lines() {
            return List.of(
                    "engine=" + engine,
                    "engineVersion=" + engineVersion,
                    "mutators=" + mutators,
                    "filters=" + filters,
                    "toolchain=" + toolchain);
        }
    }

    /**
     * A recorded per-test coverage map.
     *
     * @param key             hash over every scanned class and test class
     * @param classesCovered  classes the map has entries for; a narrower run records fewer
     * @param testsByLocation {@code class#methoddescriptor:line} to the tests that execute it
     * @param durations       test id to its baseline duration, the wall-clock backstop's input
     * @param loopIterations  test id to the loop iterations it needed unmutated, which the
     *                        runaway-loop limit is derived from
     * @param failingTests    tests that failed with no mutant applied
     */
    public record CachedCoverage(
            String key,
            Set<String> classesCovered,
            Map<String, Set<String>> testsByLocation,
            Map<String, Long> durations,
            Map<String, Long> loopIterations,
            List<String> failingTests) {
    }

    private final Path file;
    private final Header header;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Entry> reusable = new LinkedHashMap<>();
    private CachedCoverage storedCoverage;
    private CachedCoverage recordedCoverage;
    private String discardReason;

    private MutantCache(Path file, Header header) {
        this.file = file;
        this.header = header;
    }

    /** Opens the cache at {@code file}, discarding it if the header does not match. */
    public static MutantCache open(Path file, Header header) {
        MutantCache cache = new MutantCache(file, header);
        if (file == null || !Files.isRegularFile(file)) {
            return cache;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals("# " + FORMAT)) {
                cache.discardReason = "unrecognised cache format";
                return cache;
            }
            int separator = lines.indexOf(SEPARATOR);
            if (separator < 0) {
                cache.discardReason = "truncated cache file";
                return cache;
            }
            List<String> found = lines.subList(1, separator);
            List<String> expected = header.lines();
            if (!found.equals(expected)) {
                cache.discardReason = describeMismatch(expected, found);
                return cache;
            }
            String coverageKey = null;
            Set<String> coveredClasses = new LinkedHashSet<>();
            Map<String, Set<String>> testsByLocation = new LinkedHashMap<>();
            Map<String, Long> durations = new LinkedHashMap<>();
            Map<String, Long> iterations = new LinkedHashMap<>();
            List<String> failing = new ArrayList<>();

            for (String line : lines.subList(separator + 1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts[0].equals("coverage-key") && parts.length == 3) {
                    coverageKey = parts[1];
                    for (String name : parts[2].split(",")) {
                        if (!name.isEmpty()) {
                            coveredClasses.add(name);
                        }
                    }
                    continue;
                }
                if (parts[0].equals("coverage") && parts.length == 3) {
                    testsByLocation.computeIfAbsent(parts[1], k -> new LinkedHashSet<>())
                            .add(parts[2]);
                    continue;
                }
                if (parts[0].equals("test-duration") && parts.length == 3) {
                    durations.put(parts[1], Long.parseLong(parts[2]));
                    continue;
                }
                if (parts[0].equals("test-iterations") && parts.length == 3) {
                    iterations.put(parts[1], Long.parseLong(parts[2]));
                    continue;
                }
                if (parts[0].equals("failing-test") && parts.length == 2) {
                    failing.add(parts[1]);
                    continue;
                }
                if (parts.length != 7) {
                    cache.discardReason = "malformed cache entry: " + line;
                    cache.reusable.clear();
                    return cache;
                }
                cache.reusable.put(parts[0], new Entry(
                        MutantStatus.valueOf(parts[1]), parts[2],
                        parts[3].isEmpty() ? null : parts[3],
                        parts[4].isEmpty() ? null : parts[4],
                        parts[5], Integer.parseInt(parts[6]), 0));
            }
            if (coverageKey != null) {
                cache.storedCoverage = new CachedCoverage(coverageKey, coveredClasses,
                        testsByLocation, durations, iterations, failing);
            }
        } catch (IOException | IllegalArgumentException e) {
            cache.discardReason = "could not read the cache: " + e.getMessage();
            cache.reusable.clear();
        }
        return cache;
    }

    private static String describeMismatch(List<String> expected, List<String> found) {
        for (int i = 0; i < Math.min(expected.size(), found.size()); i++) {
            if (!expected.get(i).equals(found.get(i))) {
                String field = expected.get(i).split("=", 2)[0];
                return field + " changed since the cache was written ("
                        + found.get(i) + " -> " + expected.get(i) + ")";
            }
        }
        return "the cache was written by a different version of jzap";
    }

    /** Why a previous cache was not used, or null if there was nothing to discard. */
    public Optional<String> discardReason() {
        return Optional.ofNullable(discardReason);
    }

    public int reusableCount() {
        return reusable.size();
    }

    /**
     * A previous verdict for this mutant, if the rules above allow it to be reused.
     *
     * @param classHash        hash of the mutated class as it is now
     * @param coveringTests    tests that cover the mutant now, in any order
     * @param testClassHashes  test class binary name to bytecode hash, as it is now
     */
    public synchronized Optional<Mutant> reuse(Mutant mutant, String classHash, List<String> coveringTests,
                                  Map<String, String> testClassHashes) {
        Entry entry = reusable.get(mutant.key().asString());
        if (entry == null || !entry.classHash().equals(classHash)) {
            return Optional.empty();
        }
        if (entry.status() == MutantStatus.KILLED) {
            if (entry.killingTest() == null || !coveringTests.contains(entry.killingTest())) {
                return Optional.empty();
            }
            String hashNow = testClassHashes.get(declaringClassOf(entry.killingTest()));
            if (hashNow == null || !hashNow.equals(entry.killingTestHash())) {
                return Optional.empty();
            }
        } else if (!entry.coveringFingerprint()
                .equals(fingerprint(coveringTests, testClassHashes))) {
            return Optional.empty();
        }
        return Optional.of(mutant.withOutcome(entry.status(), entry.killingTest(),
                entry.coveringTests(), 0, 0));
    }

    /**
     * A previous coverage map, if it was recorded for identical bytecode and covers everything
     * this run needs.
     *
     * @param key            hash over every scanned class and test class, as they are now
     * @param classesNeeded  classes this run needs coverage for
     */
    public synchronized Optional<CachedCoverage> reuseCoverage(String key, Set<String> classesNeeded) {
        if (storedCoverage == null || !storedCoverage.key().equals(key)) {
            return Optional.empty();
        }
        if (!storedCoverage.classesCovered().containsAll(classesNeeded)) {
            // Recorded during a narrower run. Reusing it would silently report every mutant in
            // the missing classes as uncovered.
            return Optional.empty();
        }
        return Optional.of(storedCoverage);
    }

    public synchronized void recordCoverage(CachedCoverage coverage) {
        this.recordedCoverage = coverage;
    }

    /**
     * The test that killed this mutant last time, whether or not the entry is reusable.
     *
     * <p>Even an entry too stale to reuse is worth this much: the test that killed a mutant
     * before is overwhelmingly likely to kill it again, and early exit means everything tried
     * before it is wasted work.
     */
    public synchronized Optional<String> previousKillingTest(MutantKey key) {
        Entry entry = reusable.get(key.asString());
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.killingTest());
    }

    /** Records an outcome, unless it is one that cannot be reproduced. */
    public synchronized void record(Mutant analysed, String classHash, List<String> coveringTests,
                       Map<String, String> testClassHashes) {
        if (analysed.status() == null || !analysed.status().isCacheable()) {
            return;
        }
        String killingTest = analysed.killingTest();
        String killingTestHash = killingTest == null
                ? null
                : testClassHashes.get(declaringClassOf(killingTest));
        entries.put(analysed.key().asString(), new Entry(
                analysed.status(), classHash, killingTest, killingTestHash,
                fingerprint(coveringTests, testClassHashes),
                analysed.coveringTests(), analysed.testsRun()));
    }

    /**
     * Carries a reused entry forward, so reusing an entry does not delete it.
     *
     * <p>Without this, the second run after a change would lose everything the first run reused.
     */
    public synchronized void carryForward(MutantKey key) {
        Entry entry = reusable.get(key.asString());
        if (entry != null) {
            entries.put(key.asString(), entry);
        }
    }

    /** Writes the cache, sorted, so the file is diffable and runs are reproducible. */
    public synchronized void write() {
        if (file == null) {
            return;
        }
        StringBuilder text = new StringBuilder("# " + FORMAT + "\n");
        header.lines().forEach(line -> text.append(line).append('\n'));
        text.append(SEPARATOR).append('\n');

        CachedCoverage coverage = recordedCoverage != null ? recordedCoverage : storedCoverage;
        if (coverage != null) {
            text.append(String.join("\t", "coverage-key", coverage.key(),
                    String.join(",", new java.util.TreeSet<>(coverage.classesCovered())))).append('\n');
            // One line per (location, test) rather than a packed list. A JUnit unique id can
            // contain almost any character, so there is no separator that is both safe and
            // readable; more lines is the better trade in a file people are meant to read.
            new TreeMap<>(coverage.testsByLocation()).forEach((location, tests) ->
                    new java.util.TreeSet<>(tests).forEach(test ->
                            text.append(String.join("\t", "coverage", location, test))
                                    .append('\n')));
            new TreeMap<>(coverage.durations()).forEach((test, millis) ->
                    text.append(String.join("\t", "test-duration", test,
                            Long.toString(millis))).append('\n'));
            new TreeMap<>(coverage.loopIterations()).forEach((test, ticks) ->
                    text.append(String.join("\t", "test-iterations", test,
                            Long.toString(ticks))).append('\n'));
            coverage.failingTests().stream().sorted().forEach(test ->
                    text.append(String.join("\t", "failing-test", test)).append('\n'));
        }
        new TreeMap<>(entries).forEach((key, entry) -> text.append(String.join("\t",
                key,
                entry.status().name(),
                entry.classHash(),
                entry.killingTest() == null ? "" : entry.killingTest(),
                entry.killingTestHash() == null ? "" : entry.killingTestHash(),
                entry.coveringFingerprint(),
                Integer.toString(entry.coveringTests()))).append('\n'));
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the jzap cache to " + file, e);
        }
    }

    /** Identity of the covering set: which tests, and what their classes currently contain. */
    static String fingerprint(List<String> coveringTests, Map<String, String> testClassHashes) {
        List<String> parts = new ArrayList<>(coveringTests.size());
        for (String test : coveringTests) {
            String declaring = declaringClassOf(test);
            parts.add(test + ":" + testClassHashes.getOrDefault(declaring, "?"));
        }
        parts.sort(String::compareTo);
        return Hashes.ofLines(parts);
    }

    /**
     * The test class a JUnit Platform unique id belongs to.
     *
     * <p>Ids look like {@code [engine:junit-jupiter]/[class:ex.FooTest]/[method:bar()]}. Nested
     * classes appear as further {@code [nested-class:...]} segments, whose bytecode lives in the
     * outer class's file only for the declaration, so the outermost class is the right unit to
     * hash: a change anywhere in that file should invalidate.
     */
    static String declaringClassOf(String uniqueId) {
        int start = uniqueId.indexOf("[class:");
        if (start < 0) {
            return uniqueId;
        }
        int end = uniqueId.indexOf(']', start);
        return end < 0 ? uniqueId : uniqueId.substring(start + "[class:".length(), end);
    }
}
