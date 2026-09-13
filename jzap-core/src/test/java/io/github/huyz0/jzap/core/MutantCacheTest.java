package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;
import io.github.huyz0.jzap.model.MutantStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a verdict may be reused, and when the whole file must be thrown away.
 *
 * <p>Every test here is about the same risk. Reusing a verdict that is no longer true reports a
 * mutant as killed by a test that can no longer kill it, and unlike a slow run that failure is
 * silent. The rules are deliberately conservative, and these pin each one so that a later
 * optimisation cannot quietly widen them.
 *
 * <p>IncrementalCacheTest covers reuse through whole analyses. This covers the decisions
 * directly, including the malformed and mismatched files that an analysis would never produce.
 */
class MutantCacheTest {

    private static final MutantCache.Header HEADER = new MutantCache.Header(
            "schemata", "1.0", "MATH,NEGATE_CONDITIONALS", "LOOP_COUNTER", "jdk-17");

    private static MutantKey key(int line) {
        return new MutantKey("ex.Calc", "add", "(II)I", line, "MATH", 0);
    }

    private static Mutant killed(int line, String killingTest) {
        return new Mutant(key(line), ":app", "Calc.java", "replaced + with -",
                MutantStatus.KILLED, killingTest, 2, 1, 5L);
    }

    private static Mutant survived(int line) {
        return new Mutant(key(line), ":app", "Calc.java", "replaced + with -",
                MutantStatus.SURVIVED, null, 2, 2, 5L);
    }

    private static Mutant fresh(int line) {
        return Mutant.discovered(key(line), ":app", "Calc.java", "replaced + with -");
    }

    private static final String TEST_ID = "[engine:junit-jupiter]/[class:ex.CalcTest]/[method:adds()]";
    private static final String OTHER_TEST = "[engine:junit-jupiter]/[class:ex.OtherTest]/[method:t()]";

    private static Map<String, String> hashes(String... classToHash) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < classToHash.length; i += 2) {
            map.put(classToHash[i], classToHash[i + 1]);
        }
        return map;
    }

    // ------------------------------------------------------------ reuse rules

    @Test
    void aKilledMutantIsReusedWhileItsKillingTestStillCoversItUnchanged(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache first = MutantCache.open(file, HEADER);
        first.record(killed(4, TEST_ID), "class-hash", List.of(TEST_ID),
                hashes("ex.CalcTest", "test-hash"));
        first.write();

        Optional<Mutant> reused = MutantCache.open(file, HEADER)
                .reuse(fresh(4), "class-hash", List.of(TEST_ID), hashes("ex.CalcTest", "test-hash"));

        assertTrue(reused.isPresent());
        assertEquals(MutantStatus.KILLED, reused.get().status());
        assertEquals(TEST_ID, reused.get().killingTest());
    }

    @Test
    void aKilledMutantIsNotReusedWhenItsKillingTestChanged(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache first = MutantCache.open(file, HEADER);
        first.record(killed(4, TEST_ID), "class-hash", List.of(TEST_ID),
                hashes("ex.CalcTest", "test-hash"));
        first.write();

        Optional<Mutant> reused = MutantCache.open(file, HEADER)
                .reuse(fresh(4), "class-hash", List.of(TEST_ID),
                        hashes("ex.CalcTest", "test-hash-CHANGED"));

        assertEquals(Optional.empty(), reused,
                "an edited test may no longer kill it, which is the whole question");
    }

    @Test
    void aKilledMutantIsNotReusedWhenItsKillingTestNoLongerCoversIt(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache first = MutantCache.open(file, HEADER);
        first.record(killed(4, TEST_ID), "class-hash", List.of(TEST_ID),
                hashes("ex.CalcTest", "test-hash"));
        first.write();

        Optional<Mutant> reused = MutantCache.open(file, HEADER)
                .reuse(fresh(4), "class-hash", List.of(OTHER_TEST),
                        hashes("ex.OtherTest", "other-hash"));

        assertEquals(Optional.empty(), reused);
    }

    @Test
    void nothingIsReusedWhenTheMutatedClassChanged(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache first = MutantCache.open(file, HEADER);
        first.record(killed(4, TEST_ID), "class-hash", List.of(TEST_ID),
                hashes("ex.CalcTest", "test-hash"));
        first.write();

        assertEquals(Optional.empty(), MutantCache.open(file, HEADER)
                        .reuse(fresh(4), "class-hash-CHANGED", List.of(TEST_ID),
                                hashes("ex.CalcTest", "test-hash")),
                "recompiled code may not even contain this mutant any more");
    }

    @Test
    void anUnkilledMutantNeedsItsWholeCoveringSetUnchanged(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache first = MutantCache.open(file, HEADER);
        first.record(survived(4), "class-hash", List.of(TEST_ID),
                hashes("ex.CalcTest", "test-hash"));
        first.write();

        assertTrue(MutantCache.open(file, HEADER).reuse(fresh(4), "class-hash",
                List.of(TEST_ID), hashes("ex.CalcTest", "test-hash")).isPresent());

        assertEquals(Optional.empty(), MutantCache.open(file, HEADER).reuse(fresh(4), "class-hash",
                        List.of(TEST_ID, OTHER_TEST),
                        hashes("ex.CalcTest", "test-hash", "ex.OtherTest", "other")),
                "a new covering test is exactly the thing that might kill a survivor");
    }

    @Test
    void aMutantThatWasNeverRecordedIsNotReused(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache.open(file, HEADER).write();

        assertEquals(Optional.empty(), MutantCache.open(file, HEADER)
                .reuse(fresh(99), "class-hash", List.of(TEST_ID), hashes()));
    }

    @Test
    void timeoutsAndRunErrorsAreNeverStored(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache cache = MutantCache.open(file, HEADER);

        for (MutantStatus notCacheable : List.of(MutantStatus.TIMED_OUT, MutantStatus.RUN_ERROR)) {
            cache.record(fresh(4).withOutcome(notCacheable, null, 1, 1, 1L),
                    "class-hash", List.of(TEST_ID), hashes("ex.CalcTest", "h"));
        }
        cache.write();

        assertEquals(Optional.empty(), MutantCache.open(file, HEADER)
                        .reuse(fresh(4), "class-hash", List.of(TEST_ID), hashes("ex.CalcTest", "h")),
                "a wall-clock timeout is not reproducible, so reusing one reports a guess");
    }

    @Test
    void aMutantWithNoStatusIsNotStored(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache cache = MutantCache.open(file, HEADER);
        cache.record(fresh(4), "class-hash", List.of(TEST_ID), hashes());
        cache.write();

        assertEquals(Optional.empty(), MutantCache.open(file, HEADER)
                .reuse(fresh(4), "class-hash", List.of(TEST_ID), hashes()));
    }

    // ------------------------------------------------------------ carrying entries forward

    @Test
    void reusingAnEntryKeepsItForTheRunAfter(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache first = MutantCache.open(file, HEADER);
        first.record(killed(4, TEST_ID), "h", List.of(TEST_ID), hashes("ex.CalcTest", "t"));
        first.write();

        MutantCache second = MutantCache.open(file, HEADER);
        assertTrue(second.reuse(fresh(4), "h", List.of(TEST_ID), hashes("ex.CalcTest", "t"))
                .isPresent());
        second.carryForward(key(4));
        second.write();

        assertTrue(MutantCache.open(file, HEADER)
                        .reuse(fresh(4), "h", List.of(TEST_ID), hashes("ex.CalcTest", "t"))
                        .isPresent(),
                "without carrying forward, the third run would lose what the second reused");
    }

    @Test
    void thePreviousKillingTestIsRememberedEvenWhenTheEntryIsTooStaleToReuse(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache first = MutantCache.open(file, HEADER);
        first.record(killed(4, TEST_ID), "h", List.of(TEST_ID), hashes("ex.CalcTest", "t"));
        first.write();

        MutantCache second = MutantCache.open(file, HEADER);

        assertEquals(Optional.empty(),
                second.reuse(fresh(4), "h-CHANGED", List.of(TEST_ID), hashes("ex.CalcTest", "t")),
                "the class changed, so the verdict cannot be reused");
        assertEquals(Optional.of(TEST_ID), second.previousKillingTest(key(4)),
                "but it is still the test most likely to kill it again, which decides ordering");
    }

    @Test
    void thereIsNoPreviousKillingTestForAnUnknownMutant(@TempDir Path dir) {
        assertEquals(Optional.empty(),
                MutantCache.open(dir.resolve("cache.txt"), HEADER).previousKillingTest(key(1)));
    }

    // ------------------------------------------------------------ the header

    @Test
    void aChangedHeaderFieldDiscardsEverythingAndSaysWhichField(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache first = MutantCache.open(file, HEADER);
        first.record(killed(4, TEST_ID), "h", List.of(TEST_ID), hashes("ex.CalcTest", "t"));
        first.write();

        MutantCache withOtherMutators = MutantCache.open(file, new MutantCache.Header(
                "schemata", "1.0", "MATH", "LOOP_COUNTER", "jdk-17"));

        assertTrue(withOtherMutators.discardReason().isPresent());
        assertTrue(withOtherMutators.discardReason().get().contains("mutators"),
                "a user has to be able to see why their cache was dropped: "
                        + withOtherMutators.discardReason().get());
        assertEquals(Optional.empty(), withOtherMutators.reuse(fresh(4), "h",
                List.of(TEST_ID), hashes("ex.CalcTest", "t")));
    }

    @Test
    void aChangedFilterSetDiscardsTheCacheBecauseTheInventoryDiffers(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache.open(file, HEADER).write();

        MutantCache differentFilters = MutantCache.open(file, new MutantCache.Header(
                "schemata", "1.0", "MATH,NEGATE_CONDITIONALS", "", "jdk-17"));

        assertTrue(differentFilters.discardReason().get().contains("filters"),
                differentFilters.discardReason().get());
    }

    @Test
    void anUnreadableFormatIsDiscarded(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cache.txt");
        Files.writeString(file, "# some other tool's cache\nstuff\n", StandardCharsets.UTF_8);

        assertEquals(Optional.of("unrecognised cache format"),
                MutantCache.open(file, HEADER).discardReason());
    }

    @Test
    void aFileWithNoSeparatorIsTruncated(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cache.txt");
        Files.writeString(file, "# jzap cache v2\nengine=schemata\n", StandardCharsets.UTF_8);

        assertEquals(Optional.of("truncated cache file"),
                MutantCache.open(file, HEADER).discardReason());
    }

    @Test
    void anEmptyFileIsDiscardedRatherThanTrusted(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("cache.txt"), "");

        assertTrue(MutantCache.open(file, HEADER).discardReason().isPresent());
    }

    @Test
    void aHeaderWithFewerFieldsIsReportedAsAVersionDifference(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cache.txt");
        // Every field that is present matches; the file simply has fewer of them, which is what
        // a cache written by an older jzap looks like.
        Files.writeString(file, "# jzap cache v2\nengine=schemata\n---\n", StandardCharsets.UTF_8);

        assertEquals(Optional.of("the cache was written by a different version of jzap"),
                MutantCache.open(file, HEADER).discardReason());
    }

    @Test
    void aMalformedEntryDiscardsTheWholeFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cache.txt");
        MutantCache good = MutantCache.open(file, HEADER);
        good.record(killed(4, TEST_ID), "h", List.of(TEST_ID), hashes("ex.CalcTest", "t"));
        good.write();
        Files.writeString(file, Files.readString(file) + "this\tis\tnot\tan\tentry\n");

        MutantCache reopened = MutantCache.open(file, HEADER);

        assertTrue(reopened.discardReason().get().contains("malformed cache entry"),
                reopened.discardReason().get());
        assertEquals(Optional.empty(), reopened.reuse(fresh(4), "h", List.of(TEST_ID),
                        hashes("ex.CalcTest", "t")),
                "a cache half-read is one whose misses cannot be told from its absences");
    }

    @Test
    void thereIsNothingToDiscardWhenNoFileExists(@TempDir Path dir) {
        assertEquals(Optional.empty(),
                MutantCache.open(dir.resolve("absent.txt"), HEADER).discardReason());
    }

    @Test
    void aCacheWithNoFileKeepsNothingAndIsNotAnError() {
        MutantCache cache = MutantCache.open(null, HEADER);
        cache.record(killed(4, TEST_ID), "h", List.of(TEST_ID), hashes("ex.CalcTest", "t"));
        cache.write();

        assertEquals(Optional.empty(), cache.reuse(fresh(4), "h", List.of(TEST_ID),
                hashes("ex.CalcTest", "t")));
    }

    // ------------------------------------------------------------ coverage reuse

    @Test
    void aCoverageMapIsReusedOnlyForTheSameKeyAndEnoughClasses(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache first = MutantCache.open(file, HEADER);
        first.recordCoverage(new MutantCache.CachedCoverage("key-1",
                Set.of("ex.Calc"),
                Map.of("ex.Calc#add(II)I:4", Set.of(TEST_ID)),
                Map.of(TEST_ID, 12L),
                Map.of(TEST_ID, 3L),
                Map.of(TEST_ID, ":app"),
                List.of()));
        first.write();

        MutantCache reopened = MutantCache.open(file, HEADER);

        assertTrue(reopened.reuseCoverage("key-1", Set.of("ex.Calc")).isPresent());
        assertEquals(Optional.empty(), reopened.reuseCoverage("key-2", Set.of("ex.Calc")),
                "any class having changed invalidates the whole map");
        assertEquals(Optional.empty(),
                reopened.reuseCoverage("key-1", Set.of("ex.Calc", "ex.Other")),
                "a map recorded during a narrow run must not be mistaken for a complete one");
    }

    @Test
    void aReusedCoverageMapKeepsTheDetailTheEngineNeeds(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache first = MutantCache.open(file, HEADER);
        first.recordCoverage(new MutantCache.CachedCoverage("key-1",
                Set.of("ex.Calc"),
                Map.of("ex.Calc#add(II)I:4", Set.of(TEST_ID, OTHER_TEST)),
                Map.of(TEST_ID, 12L, OTHER_TEST, 30L),
                Map.of(TEST_ID, 3L, OTHER_TEST, 9L),
                Map.of(TEST_ID, ":app", OTHER_TEST, ":lib"),
                List.of(OTHER_TEST)));
        first.write();

        MutantCache.CachedCoverage reused = MutantCache.open(file, HEADER)
                .reuseCoverage("key-1", Set.of("ex.Calc")).orElseThrow();

        assertEquals(Set.of(TEST_ID, OTHER_TEST),
                reused.testsByLocation().get("ex.Calc#add(II)I:4"));
        assertEquals(12L, reused.durations().get(TEST_ID), "the timeout backstop is derived from this");
        assertEquals(9L, reused.loopIterations().get(OTHER_TEST),
                "and the runaway limit from this");
        assertEquals(":lib", reused.testModules().get(OTHER_TEST),
                "a reused map still has to know which module can run each test");
        assertEquals(List.of(OTHER_TEST), reused.failingTests(),
                "a test that was already red must stay excluded from selection");
    }

    @Test
    void thereIsNoCoverageToReuseFromAFreshCache(@TempDir Path dir) {
        assertEquals(Optional.empty(), MutantCache.open(dir.resolve("absent.txt"), HEADER)
                .reuseCoverage("any", Set.of("ex.Calc")));
    }

    @Test
    void aCoverageMapSurvivesARunThatRecordedNoneOfItsOwn(@TempDir Path dir) {
        Path file = dir.resolve("cache.txt");
        MutantCache first = MutantCache.open(file, HEADER);
        first.recordCoverage(new MutantCache.CachedCoverage("key-1", Set.of("ex.Calc"),
                Map.of(), Map.of(), Map.of(), Map.of(), List.of()));
        first.write();

        // A diff-scoped run with nothing in scope writes the cache without touching coverage.
        MutantCache second = MutantCache.open(file, HEADER);
        second.write();

        assertTrue(MutantCache.open(file, HEADER).reuseCoverage("key-1", Set.of("ex.Calc"))
                        .isPresent(),
                "a run that recorded nothing must not erase what the last one learned");
    }

    // ------------------------------------------------------------ the file itself

    @Test
    void theFileIsSortedSoADiffOfItShowsWhatChanged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cache.txt");
        MutantCache cache = MutantCache.open(file, HEADER);
        cache.record(killed(9, TEST_ID), "h", List.of(TEST_ID), hashes("ex.CalcTest", "t"));
        cache.record(killed(4, TEST_ID), "h", List.of(TEST_ID), hashes("ex.CalcTest", "t"));
        cache.recordCoverage(new MutantCache.CachedCoverage("key", Set.of("ex.Calc"),
                Map.of("b:2", Set.of(OTHER_TEST), "a:1", Set.of(TEST_ID)),
                Map.of(), Map.of(), Map.of(), List.of(OTHER_TEST, TEST_ID)));
        cache.write();

        List<String> lines = Files.readAllLines(file);
        assertEquals("# jzap cache v2", lines.get(0));
        assertTrue(lines.contains("---"), "the header is separated from the body");

        List<String> coverage = lines.stream().filter(l -> l.startsWith("coverage\t")).toList();
        assertEquals(coverage.stream().sorted().toList(), coverage, "coverage lines are sorted");

        List<String> failing = lines.stream().filter(l -> l.startsWith("failing-test\t")).toList();
        assertEquals(failing.stream().sorted().toList(), failing, "and so are failing tests");

        int firstEntry = indexOfEntry(lines, "::4::");
        int secondEntry = indexOfEntry(lines, "::9::");
        assertTrue(firstEntry < secondEntry, "entries are in key order, not insertion order");
    }

    private static int indexOfEntry(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        throw new IllegalArgumentException("no line containing " + needle + " in " + lines);
    }

    @Test
    void writingCreatesTheDirectoryItNeeds(@TempDir Path dir) {
        Path file = dir.resolve("nested/deeper/cache.txt");
        MutantCache.open(file, HEADER).write();

        assertTrue(Files.isRegularFile(file), "a cache directory the user named may not exist yet");
    }

    @Test
    void twoWritesOfTheSameContentProduceTheSameBytes(@TempDir Path dir) throws Exception {
        Path one = dir.resolve("one.txt");
        Path two = dir.resolve("two.txt");
        for (Path file : List.of(one, two)) {
            MutantCache cache = MutantCache.open(file, HEADER);
            cache.record(killed(4, TEST_ID), "h", List.of(TEST_ID), hashes("ex.CalcTest", "t"));
            cache.record(survived(9), "h", List.of(OTHER_TEST), hashes("ex.OtherTest", "o"));
            cache.write();
        }

        assertEquals(Files.readString(one), Files.readString(two),
                "a cache that differed run to run would defeat the build cache it feeds");
    }

    // ------------------------------------------------------------ test ids

    @Test
    void aReusedVerdictReportsThisRunsCoveringCount(@TempDir Path dir) {
        // A killed mutant is reused on the strength of its killing test, so the rest of the
        // covering set may have changed since. The count describes the current run.
        Path file = dir.resolve("cache.txt");
        MutantCache first = MutantCache.open(file, HEADER);
        first.record(killed(4, TEST_ID), "class-hash", List.of(TEST_ID),
                hashes("ex.CalcTest", "test-hash"));
        first.write();

        Optional<Mutant> reused = MutantCache.open(file, HEADER)
                .reuse(fresh(4), "class-hash", List.of(TEST_ID, OTHER_TEST),
                        hashes("ex.CalcTest", "test-hash", "ex.OtherTest", "o"));

        assertTrue(reused.isPresent());
        assertEquals(2, reused.get().coveringTests(),
                "two tests cover it now, whatever the entry was written with");
    }

    @Test
    void theDeclaringClassOfAUniqueIdIsTheClassThatHoldsTheTestsBytecode() {
        assertEquals("ex.CalcTest", MutantCache.declaringClassOf(TEST_ID));
    }

    @Test
    void aNestedTestIsAttributedToTheNestedClassFile() {
        // javac compiles a @Nested class to Outer$Inner.class and leaves Outer.class
        // byte-identical, so hashing the outer class cannot see an edit to a nested test -- and a
        // survivor covered only by that test would be reused after the edit that kills it.
        assertEquals("ex.OuterTest$Inner", MutantCache.declaringClassOf(
                "[engine:junit-jupiter]/[class:ex.OuterTest]/[nested-class:Inner]/[method:t()]"));
        assertEquals("ex.OuterTest$Inner$Deeper", MutantCache.declaringClassOf(
                "[engine:junit-jupiter]/[class:ex.OuterTest]/[nested-class:Inner]"
                        + "/[nested-class:Deeper]/[method:t()]"));
    }

    @Test
    void aKotestSpecIsAttributedToItsSpecClass() {
        // Kotest names the class with [spec:...] and never emits [class:...]. These are the ids
        // the kotest fixture actually produces.
        assertEquals("ktest.ShippingFunSpec", MutantCache.declaringClassOf(
                "[engine:kotest]/[spec:ktest.ShippingFunSpec]/[test:small baskets pay shipping]"));
        assertEquals("ktest.SubtotalBehaviorSpec", MutantCache.declaringClassOf(
                "[engine:kotest]/[spec:ktest.SubtotalBehaviorSpec]"
                        + "/[test:a basket with three items]/[test:the subtotal is taken]"
                        + "/[test:it is the sum of the prices]"));
    }

    @Test
    void aSurvivorUnderAnEngineWithItsOwnIdFormatIsNotReusedAfterItsTestChanges(
            @TempDir Path dir) {
        // The soundness question behind the two tests above. If the declaring class cannot be
        // recovered from the id, every test hashes as "?" -- the covering fingerprint then stops
        // depending on the test's bytecode at all, and a survivor is reused even after the test
        // that now kills it was edited.
        String kotestId = "[engine:kotest]/[spec:ktest.ShippingFunSpec]/[test:pays shipping]";
        Path file = dir.resolve("cache.txt");
        MutantCache first = MutantCache.open(file, HEADER);
        first.record(survived(9), "class-hash", List.of(kotestId),
                hashes("ktest.ShippingFunSpec", "spec-hash"));
        first.write();

        Optional<Mutant> reused = MutantCache.open(file, HEADER)
                .reuse(fresh(9), "class-hash", List.of(kotestId),
                        hashes("ktest.ShippingFunSpec", "spec-hash-CHANGED"));

        assertEquals(Optional.empty(), reused,
                "the spec was edited, so the survivor has to be re-run");
    }

    @Test
    void anIdThatIsNotAUniqueIdIsUsedAsItStands() {
        assertEquals("something-else", MutantCache.declaringClassOf("something-else"),
                "an engine free to invent its own id format must not break the cache");
        assertEquals("[class:unterminated", MutantCache.declaringClassOf("[class:unterminated"));
    }

    @Test
    void theCoveringFingerprintChangesWithTheSetAndWithEachTestsClass() {
        String one = MutantCache.fingerprint(List.of(TEST_ID), hashes("ex.CalcTest", "a"));

        assertEquals(one, MutantCache.fingerprint(List.of(TEST_ID), hashes("ex.CalcTest", "a")),
                "the same inputs must fingerprint the same way");
        assertFalse(one.equals(MutantCache.fingerprint(List.of(TEST_ID),
                        hashes("ex.CalcTest", "b"))),
                "an edited test class has to change it");
        assertFalse(one.equals(MutantCache.fingerprint(List.of(TEST_ID, OTHER_TEST),
                        hashes("ex.CalcTest", "a", "ex.OtherTest", "o"))),
                "so does an added covering test");
    }

    @Test
    void theFingerprintDoesNotDependOnTheOrderTestsWereFoundIn() {
        Map<String, String> classes = hashes("ex.CalcTest", "a", "ex.OtherTest", "o");

        assertEquals(MutantCache.fingerprint(List.of(TEST_ID, OTHER_TEST), classes),
                MutantCache.fingerprint(List.of(OTHER_TEST, TEST_ID), classes),
                "selection order is a performance choice and must not invalidate a verdict");
    }

    @Test
    void aTestWhoseClassHasNoHashStillFingerprints() {
        assertFalse(MutantCache.fingerprint(List.of(TEST_ID), hashes()).isBlank(),
                "a test whose class was not scanned must not produce a null fingerprint");
    }
}
