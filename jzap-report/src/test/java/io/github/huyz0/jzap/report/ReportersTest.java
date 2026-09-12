package io.github.huyz0.jzap.report;

import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;
import io.github.huyz0.jzap.model.MutantStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportersTest {

    private static Mutant mutant(String mutator, int line, MutantStatus status) {
        return new Mutant(
                new MutantKey("ex.Calc", "add", "(II)I", line, mutator, 0),
                ":app", "Calc.java", "replaced addition with subtraction",
                status, status == MutantStatus.KILLED ? "ex.CalcTest#adds" : null,
                status == MutantStatus.NO_COVERAGE ? 0 : 2,
                status == MutantStatus.NO_COVERAGE ? 0 : 1, 12L);
    }

    private static AnalysisResult result() {
        return new AnalysisResult(
                List.of(
                        mutant("MATH", 4, MutantStatus.KILLED),
                        mutant("PRIMITIVE_RETURNS", 5, MutantStatus.SURVIVED),
                        mutant("NEGATE_CONDITIONALS", 9, MutantStatus.NO_COVERAGE)),
                Map.of("coverage", 100L, "execution", 250L),
                3, "all mutants in all target classes", "naive", List.of());
    }

    @Test
    void consoleLeadsWithSurvivorsBecauseThoseAreTheActionablePart(@TempDir Path dir) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        new ConsoleReporter(new PrintStream(buffer, true, StandardCharsets.UTF_8))
                .write(result(), ReportContext.of(dir, List.of()));

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Surviving mutants (1)"), output);
        assertTrue(output.indexOf("Surviving mutants") < output.indexOf("Mutation score"),
                "survivors should appear before the score");
        assertTrue(output.contains("Mutation score 33.3%"), output);
        assertTrue(output.contains("test strength 50.0%"), output);
    }

    @Test
    void consoleWarnsLoudlyWhenTheSuiteIsAlreadyRed(@TempDir Path dir) {
        AnalysisResult red = new AnalysisResult(result().mutants(), Map.of(), 3, "scope", "naive",
                List.of("ex.CalcTest#broken"));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        new ConsoleReporter(new PrintStream(buffer, true, StandardCharsets.UTF_8))
                .write(red, ReportContext.of(dir, List.of()));

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("WARNING"), output);
        assertTrue(output.contains("ex.CalcTest#broken"), output);
        assertTrue(output.contains("killed for the wrong reason"),
                "the warning must say why it matters, not just that it happened");
    }

    @Test
    void nativeJsonIsDeterministicAndCarriesStableKeys(@TempDir Path dir) throws Exception {
        new NativeJsonReporter().write(result(), ReportContext.of(dir, List.of()));
        String first = Files.readString(dir.resolve(NativeJsonReporter.FILE_NAME));
        new NativeJsonReporter().write(result(), ReportContext.of(dir, List.of()));
        String second = Files.readString(dir.resolve(NativeJsonReporter.FILE_NAME));

        assertEquals(first, second, "the same result must serialise to identical bytes");
        assertTrue(first.contains("ex.Calc::add(II)I::4::MATH#0"), first);
        assertTrue(first.contains("\"status\": \"SURVIVED\""), first);
    }

    @Test
    void elementsJsonUsesTheSchemaVocabulary(@TempDir Path dir) throws Exception {
        new ElementsJsonReporter().write(result(), ReportContext.of(dir, List.of()));
        String json = Files.readString(dir.resolve(ElementsJsonReporter.FILE_NAME));

        assertTrue(json.contains("mutation-testing-report-schema.json"), json);
        assertTrue(json.contains("\"Killed\""), json);
        assertTrue(json.contains("\"Survived\""), json);
        assertTrue(json.contains("\"NoCoverage\""), json);
        assertFalse(json.contains("\"KILLED\""), "schema statuses are not jzap's enum names");
    }

    @Test
    void elementsStatusMappingCoversEveryJzapStatus() {
        for (MutantStatus status : MutantStatus.values()) {
            assertFalse(ElementsJsonReporter.schemaStatus(status).isBlank(),
                    "no schema status for " + status);
        }
        assertEquals("CompileError", ElementsJsonReporter.schemaStatus(MutantStatus.NON_VIABLE),
                "a mutant that will not verify says nothing about the test suite");
        assertEquals("Timeout", ElementsJsonReporter.schemaStatus(MutantStatus.TIMED_OUT));
    }

    @Test
    void annotationsContainOnlySurvivors(@TempDir Path dir) throws Exception {
        new AnnotationsJsonReporter().write(result(), ReportContext.of(dir, List.of()));
        String json = Files.readString(dir.resolve(AnnotationsJsonReporter.FILE_NAME));

        assertTrue(json.contains("PRIMITIVE_RETURNS"), json);
        assertFalse(json.contains("\"mutator\": \"MATH\""),
                "a killed mutant needs no pull-request comment");
        assertFalse(json.contains("NEGATE_CONDITIONALS"),
                "uncovered code is a different conversation from a surviving mutant");
    }

    @Test
    void htmlIsSelfContainedAndEscapesSource(@TempDir Path dir) throws Exception {
        AnalysisResult withMarkup = new AnalysisResult(
                List.of(new Mutant(new MutantKey("ex.Calc", "cmp", "(II)Z", 4, "MATH", 0),
                        ":app", "Calc.java", "a < b became a <= b", MutantStatus.SURVIVED,
                        null, 1, 1, 1L)),
                Map.of(), 1, "scope", "naive", List.of());

        new HtmlReporter().write(withMarkup, ReportContext.of(dir, List.of()));
        String html = Files.readString(dir.resolve(HtmlReporter.FILE_NAME));

        assertTrue(html.startsWith("<!doctype html>"), html.substring(0, 40));
        assertFalse(html.contains("<script"), "the report must not need to execute anything");
        assertFalse(html.contains("http://"), "and must not fetch anything");
        assertTrue(html.contains("a &lt; b became a &lt;= b"), "source must be escaped");
    }

    @Test
    void unknownReporterNamesTheOnesThatExist() {
        Reporters reporters = new Reporters(System.out);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> reporters.byId("teletype"));
        assertTrue(e.getMessage().contains("console"), e.getMessage());
        assertTrue(e.getMessage().contains("elements"), e.getMessage());
    }

    @Test
    void sourceRelativePathIsDerivedFromThePackage() {
        Mutant inner = new Mutant(new MutantKey("ex.deep.Outer$Inner", "f", "()V", 3, "MATH", 0),
                ":app", "Outer.java", "d", MutantStatus.KILLED, null, 1, 1, 1L);
        assertEquals("ex/deep/Outer.java", SourceLocator.relativePath(inner),
                "an inner class reports against the file its outer class lives in");
    }
}
