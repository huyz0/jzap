package io.github.huyz0.jzap.report;

import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;
import io.github.huyz0.jzap.model.MutantStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding the source behind a mutant.
 *
 * <p>A report that cannot find the source still has to be a report: the mutant key and the
 * verdict are the information, and the source line is the convenience. Every lookup here
 * therefore has to degrade to empty rather than fail, which is what most of these assert.
 */
class SourceLocatorTest {

    private static Mutant mutantIn(String className, String sourceFile, int line) {
        return new Mutant(
                new MutantKey(className, "add", "(II)I", line, "MATH", 0),
                ":app", sourceFile, "replaced addition with subtraction",
                MutantStatus.SURVIVED, null, 1, 1, 5L);
    }

    private static Path sourceTree(Path dir) throws Exception {
        Path root = dir.resolve("src/main/java");
        Path file = root.resolve("ex/Calc.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package ex;

                class Calc {
                    int add(int a, int b) {
                        return a + b;
                    }
                }
                """, StandardCharsets.UTF_8);
        return root;
    }

    // ------------------------------------------------------------ the relative path

    @Test
    void theRelativePathComesFromThePackageAndTheRecordedFileName() {
        assertEquals("ex/Calc.java",
                SourceLocator.relativePath(mutantIn("ex.Calc", "Calc.java", 5)));
        assertEquals("a/b/c/Deep.java",
                SourceLocator.relativePath(mutantIn("a.b.c.Deep", "Deep.java", 1)));
    }

    @Test
    void aClassInTheDefaultPackageHasNoDirectory() {
        assertEquals("Root.java", SourceLocator.relativePath(mutantIn("Root", "Root.java", 1)));
    }

    @Test
    void aKotlinFileKeepsItsOwnNameRatherThanTheClassName() {
        // Kotlin puts many classes in one file, and top-level functions in FooKt. The name
        // recorded in the class file is the only thing that points at the right source.
        assertEquals("ex/utils.kt",
                SourceLocator.relativePath(mutantIn("ex.UtilsKt", "utils.kt", 3)));
    }

    @Test
    void withoutARecordedFileNameItIsGuessedFromTheClassName() {
        assertEquals("ex/Calc.java", SourceLocator.relativePath(mutantIn("ex.Calc", null, 5)));
        assertEquals("ex/Calc.java", SourceLocator.relativePath(mutantIn("ex.Calc", "  ", 5)));
    }

    @Test
    void anInnerClassIsAttributedToItsOuterClassesFile() {
        assertEquals("ex/Outer.java",
                SourceLocator.relativePath(mutantIn("ex.Outer$Inner", null, 5)),
                "the guess has to strip the nesting, or the file would never be found");
        assertEquals("ex/Outer.java",
                SourceLocator.relativePath(mutantIn("ex.Outer$1", null, 5)),
                "including anonymous classes");
    }

    // ------------------------------------------------------------ locating and reading

    @Test
    void locatesAFileUnderOneOfTheSourceRoots(@TempDir Path dir) throws Exception {
        Path root = sourceTree(dir);
        SourceLocator locator = new SourceLocator(List.of(
                dir.resolve("src/test/java"), root));

        Optional<Path> found = locator.locate(mutantIn("ex.Calc", "Calc.java", 5));

        assertTrue(found.isPresent(), "the second root holds it");
        assertEquals(root.resolve("ex/Calc.java"), found.get());
    }

    @Test
    void aMutantWhoseSourceIsNotThereIsReportedWithoutIt(@TempDir Path dir) throws Exception {
        SourceLocator locator = new SourceLocator(List.of(sourceTree(dir)));

        Mutant elsewhere = mutantIn("other.Absent", "Absent.java", 1);

        assertEquals(Optional.empty(), locator.locate(elsewhere));
        assertEquals(Optional.empty(), locator.read(elsewhere));
        assertEquals(Optional.empty(), locator.line(elsewhere));
    }

    @Test
    void withNoSourceRootsAtAllNothingIsFound() {
        SourceLocator locator = new SourceLocator(List.of());

        assertEquals(Optional.empty(), locator.locate(mutantIn("ex.Calc", "Calc.java", 5)));
    }

    @Test
    void readsTheWholeFile(@TempDir Path dir) throws Exception {
        SourceLocator locator = new SourceLocator(List.of(sourceTree(dir)));

        Optional<String> source = locator.read(mutantIn("ex.Calc", "Calc.java", 5));

        assertTrue(source.isPresent());
        assertTrue(source.get().contains("return a + b;"), source.get());
    }

    @Test
    void readsTheOneLineAMutantSitsOnTrimmed(@TempDir Path dir) throws Exception {
        SourceLocator locator = new SourceLocator(List.of(sourceTree(dir)));

        assertEquals("return a + b;", locator.line(mutantIn("ex.Calc", "Calc.java", 5)).get(),
                "the line is shown next to the mutant, so leading indentation is noise");
        assertEquals("package ex;", locator.line(mutantIn("ex.Calc", "Calc.java", 1)).get());
    }

    @Test
    void aLineNumberOutsideTheFileYieldsNothing(@TempDir Path dir) throws Exception {
        SourceLocator locator = new SourceLocator(List.of(sourceTree(dir)));

        assertEquals(Optional.empty(), locator.line(mutantIn("ex.Calc", "Calc.java", 9999)),
                "a stale line number must not index past the end of the file");
        assertEquals(Optional.empty(), locator.line(mutantIn("ex.Calc", "Calc.java", 0)),
                "line 0 means the class had no debug information");
    }

    @Test
    void lookupsAreCachedSoAReportDoesNotWalkTheTreePerMutant(@TempDir Path dir) throws Exception {
        Path root = sourceTree(dir);
        SourceLocator locator = new SourceLocator(List.of(root));
        Mutant mutant = mutantIn("ex.Calc", "Calc.java", 5);

        assertTrue(locator.locate(mutant).isPresent());
        Files.delete(root.resolve("ex/Calc.java"));

        assertTrue(locator.locate(mutant).isPresent(),
                "the second lookup is answered from the cache, which is the point of having one");
        assertFalse(locator.read(mutant).isPresent(),
                "but reading it now fails, and that has to degrade to empty rather than throw");
    }

    @Test
    void aDirectoryWhereAFileWasExpectedIsNotMistakenForSource(@TempDir Path dir)
            throws Exception {
        Path root = dir.resolve("src");
        Files.createDirectories(root.resolve("ex/Calc.java"));

        SourceLocator locator = new SourceLocator(List.of(root));

        assertEquals(Optional.empty(), locator.locate(mutantIn("ex.Calc", "Calc.java", 1)));
    }
}
