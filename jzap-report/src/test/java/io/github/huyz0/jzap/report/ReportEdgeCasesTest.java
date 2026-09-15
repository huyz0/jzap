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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report cases a healthy run never produces.
 *
 * <p>A reporter is the only part of jzap a user always sees, and the runs worth reporting well
 * are the unusual ones: an empty scope, a suite that was already red, a source tree that is not
 * there. Each has to produce something a reader can act on rather than a blank page or a stack
 * trace.
 */
class ReportEdgeCasesTest {

    private static Mutant mutant(int line, MutantStatus status) {
        return new Mutant(
                new MutantKey("ex.Calc", "add", "(II)I", line, "MATH", 0),
                ":app", "Calc.java", "replaced addition with subtraction",
                status, status == MutantStatus.KILLED ? "ex.CalcTest#adds" : null,
                status == MutantStatus.NO_COVERAGE ? 0 : 2,
                status == MutantStatus.NO_COVERAGE ? 0 : 1, 12L);
    }

    private static AnalysisResult resultOf(List<Mutant> mutants, List<String> failingBaseline) {
        return new AnalysisResult(mutants, Map.of("coverage", 10L), mutants.size(),
                "all mutants in all target classes", "schemata", failingBaseline, 0);
    }

    private static String html(AnalysisResult result, Path dir, List<Path> sourceRoots)
            throws Exception {
        new HtmlReporter().write(result, new ReportContext(dir, sourceRoots, null, 80, 60));
        return Files.readString(dir.resolve(HtmlReporter.FILE_NAME), StandardCharsets.UTF_8);
    }

    private static String console(AnalysisResult result, Path dir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ConsoleReporter(new PrintStream(out, true, StandardCharsets.UTF_8))
                .write(result, ReportContext.of(dir, List.of()));
        return out.toString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ empty runs

    @Test
    void anEmptyRunStillProducesAReadableHtmlReport(@TempDir Path dir) throws Exception {
        String html = html(resultOf(List.of(), List.of()), dir, List.of());

        assertTrue(html.contains("<html"), "it still has to be a page");
        assertTrue(html.contains("</html>"));
        assertTrue(html.contains("jzap mutation report"), html);
    }

    @Test
    void anEmptyRunDoesNotClaimAScore(@TempDir Path dir) {
        String text = console(resultOf(List.of(), List.of()), dir);

        assertFalse(text.contains("NaN"),
                "no mutants means no score, and NaN is not a report: " + text);
    }

    // ------------------------------------------------------------ a red baseline

    @Test
    void htmlWarnsAboutAFailingBaselineAndNamesTheTests(@TempDir Path dir) throws Exception {
        String html = html(resultOf(List.of(mutant(4, MutantStatus.KILLED)),
                List.of("ex.BrokenTest#alreadyFails", "ex.OtherTest#alsoFails")), dir, List.of());

        assertTrue(html.contains("2 test(s) already fail"), html);
        assertTrue(html.contains("ex.BrokenTest#alreadyFails"),
                "a reader has to know which tests to fix: " + html);
        assertTrue(html.contains("ex.OtherTest#alsoFails"), html);
        assertTrue(html.contains("before trusting the verdicts"),
                "and why it matters: " + html);
    }

    @Test
    void aHealthyRunHasNoSuchWarning(@TempDir Path dir) throws Exception {
        String html = html(resultOf(List.of(mutant(4, MutantStatus.KILLED)), List.of()),
                dir, List.of());

        assertFalse(html.contains("already fail"), html);
    }

    // ------------------------------------------------------------ ordering

    @Test
    void htmlPutsSurvivorsFirstThenUncoveredThenKilled(@TempDir Path dir) throws Exception {
        String html = html(resultOf(List.of(
                mutant(1, MutantStatus.KILLED),
                mutant(2, MutantStatus.NO_COVERAGE),
                mutant(3, MutantStatus.SURVIVED)), List.of()), dir, List.of());

        // Searched in the body only: the stylesheet names every status class up front.
        String body = html.substring(html.indexOf("</style>"));
        int survived = body.indexOf("SURVIVED");
        int uncovered = body.indexOf("NO_COVERAGE");
        int killed = body.indexOf("KILLED");

        assertTrue(survived < uncovered,
                "survivors are the only rows that ask the reader to do something");
        assertTrue(uncovered < killed, "and uncovered code is the next most actionable");
    }

    @Test
    void withinOneStatusTheOrderIsBySourceLine(@TempDir Path dir) throws Exception {
        String html = html(resultOf(List.of(
                mutant(9, MutantStatus.SURVIVED),
                mutant(4, MutantStatus.SURVIVED)), List.of()), dir, List.of());

        assertTrue(html.indexOf(">4<") < html.indexOf(">9<"),
                "a reader reads a file downwards: " + html);
    }

    // ------------------------------------------------------------ source rendering

    @Test
    void theSourceLineIsShownWhenItCanBeFound(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src");
        Files.createDirectories(src.resolve("ex"));
        Files.writeString(src.resolve("ex/Calc.java"), """
                package ex;
                class Calc {
                    int add(int a, int b) { return a + b; }
                }
                """);

        String html = html(resultOf(List.of(mutant(3, MutantStatus.SURVIVED)), List.of()),
                dir.resolve("out"), List.of(src));

        assertTrue(html.contains("return a + b;"),
                "the point of a source root is to show the code that changed: " + html);
    }

    @Test
    void aMissingSourceTreeStillProducesTheReport(@TempDir Path dir) throws Exception {
        String html = html(resultOf(List.of(mutant(3, MutantStatus.SURVIVED)), List.of()),
                dir, List.of(dir.resolve("no-such-source")));

        assertTrue(html.contains("SURVIVED"),
                "the key and the verdict are the information; the source line is a convenience");
    }

    @Test
    void sourceIsEscapedSoItCannotBreakOutOfThePage(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src");
        Files.createDirectories(src.resolve("ex"));
        Files.writeString(src.resolve("ex/Calc.java"), """
                package ex;
                class Calc {
                    String s = "<script>alert('x')</script> & \\"quoted\\"";
                }
                """);

        String html = html(resultOf(List.of(mutant(3, MutantStatus.SURVIVED)), List.of()),
                dir.resolve("out"), List.of(src));

        assertFalse(html.contains("<script>alert"),
                "source is untrusted input as far as the report is concerned: " + html);
        assertTrue(html.contains("&lt;script&gt;"), html);
        assertTrue(html.contains("&amp;"), html);
    }

    @Test
    void theReportDirectoryIsCreatedIfItIsNotThere(@TempDir Path dir) throws Exception {
        Path nested = dir.resolve("a/b/c");

        html(resultOf(List.of(mutant(4, MutantStatus.KILLED)), List.of()), nested, List.of());

        assertTrue(Files.isRegularFile(nested.resolve(HtmlReporter.FILE_NAME)));
    }

    // ------------------------------------------------------------ every status renders

    @Test
    void everyStatusAppearsInTheHtmlWithoutBreakingIt(@TempDir Path dir) throws Exception {
        List<Mutant> all = List.of(
                mutant(1, MutantStatus.KILLED),
                mutant(2, MutantStatus.SURVIVED),
                mutant(3, MutantStatus.NO_COVERAGE),
                mutant(4, MutantStatus.TIMED_OUT),
                mutant(5, MutantStatus.NON_VIABLE),
                mutant(6, MutantStatus.RUN_ERROR));

        String html = html(resultOf(all, List.of()), dir, List.of());

        for (MutantStatus status : MutantStatus.values()) {
            assertTrue(html.contains(status.name()), status + " is missing from " + html);
        }
        assertEquals(count(html, "<tr"), count(html, "</tr>"), "the table has to stay balanced");
    }

    @Test
    void consoleReportsEveryStatusItWasGiven(@TempDir Path dir) {
        String text = console(resultOf(List.of(
                mutant(1, MutantStatus.KILLED),
                mutant(2, MutantStatus.SURVIVED),
                mutant(3, MutantStatus.TIMED_OUT)), List.of()), dir);

        assertTrue(text.contains("SURVIVED") || text.contains("survived"), text);
    }

    // -------------------------------------------------- mutants outside the score

    @Test
    void consoleSaysWhichMutantsAreOutsideBothPercentages(@TempDir Path dir) {
        String text = console(resultOf(List.of(
                mutant(1, MutantStatus.KILLED),
                mutant(2, MutantStatus.SURVIVED),
                mutant(3, MutantStatus.NON_VIABLE),
                mutant(4, MutantStatus.RUN_ERROR)), List.of()), dir);

        // Six of the nine lines of totals reconcile on their own; these are the ones that do not,
        // so a reader comparing "total 4" against a score over 2 has an answer on the page.
        assertTrue(text.contains("total            4"), text);
        assertTrue(text.contains("Mutation score 50.0%"), text);
        assertTrue(text.contains("2 of 4 mutants are outside both figures"), text);
        assertTrue(text.contains("1 non-viable"), text);
        assertTrue(text.contains("1 run error"), text);
    }

    @Test
    void consoleSaysNothingAboutExclusionsWhenThereAreNone(@TempDir Path dir) {
        String text = console(resultOf(List.of(
                mutant(1, MutantStatus.KILLED),
                mutant(2, MutantStatus.NO_COVERAGE)), List.of()), dir);

        assertFalse(text.contains("outside both figures"),
                "an ordinary run must not be told about an exclusion that did not happen");
    }

    @Test
    void htmlNamesTheExcludedMutantsSoTheTilesAddUp(@TempDir Path dir) throws Exception {
        String html = html(resultOf(List.of(
                mutant(1, MutantStatus.KILLED),
                mutant(2, MutantStatus.NON_VIABLE)), List.of()), dir, List.of());

        assertTrue(html.contains("not scored"), html);
        assertTrue(html.contains("100.0%"), "the score is over the one mutant that was judged");
    }

    @Test
    void nativeJsonCarriesTheDenominatorsAndNotOnlyTheRatios(@TempDir Path dir) throws Exception {
        new NativeJsonReporter().write(resultOf(List.of(
                mutant(1, MutantStatus.KILLED),
                mutant(2, MutantStatus.SURVIVED),
                mutant(3, MutantStatus.NO_COVERAGE),
                mutant(4, MutantStatus.RUN_ERROR)), List.of()),
                ReportContext.of(dir, List.of()));
        String json = Files.readString(dir.resolve(NativeJsonReporter.FILE_NAME),
                StandardCharsets.UTF_8);

        // Without these a consumer aggregating several runs can only weight by total mutants,
        // which is not what either percentage was computed over.
        assertTrue(json.contains("\"scoredMutants\": 3"), json);
        assertTrue(json.contains("\"unscoredMutants\": 1"), json);
        assertTrue(json.contains("\"coveredMutants\": 2"), json);
        assertTrue(json.contains("\"detectedMutants\": 1"), json);
    }

    // -------------------------------------------------------- the agent reporter

    private static String agent(AnalysisResult result, Path dir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Reporters(new PrintStream(out, true, StandardCharsets.UTF_8))
                .byId("agent").write(result, ReportContext.of(dir, List.of()));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theAgentReportCarriesTheFindingsAndNotTheKilledMutants(@TempDir Path dir) {
        String text = agent(resultOf(List.of(
                mutant(1, MutantStatus.KILLED),
                mutant(2, MutantStatus.SURVIVED),
                mutant(3, MutantStatus.NO_COVERAGE)), List.of()), dir);

        assertTrue(text.startsWith("jzap: 1 survived, 1 uncovered of 3 mutants"),
                "the headline has to survive a truncated read: " + text);
        assertTrue(text.contains("ex/Calc.java"), text);
        assertTrue(text.contains("  2 MATH"), "line, mutator, description: " + text);
        assertFalse(text.contains("KILLED"),
                "a killed mutant is the good outcome and there is nothing to do about it: " + text);
        assertTrue(text.indexOf("survived:") < text.indexOf("uncovered:"),
                "survivors are the actionable half and must come first: " + text);
    }

    @Test
    void theAgentReportIsOneLineWhenThereIsNothingToDo(@TempDir Path dir) {
        String text = agent(resultOf(List.of(mutant(1, MutantStatus.KILLED)), List.of()), dir);

        assertEquals(1, text.strip().lines().count(),
                "nothing actionable should cost one line, not a heading with nothing under it: "
                        + text);
    }

    /**
     * A red baseline is the one thing that must not be abbreviated away.
     *
     * <p>Every verdict is suspect while a test already fails, so an agent that acts on the
     * findings without seeing this does the wrong work confidently.
     */
    @Test
    void theAgentReportLeadsWithARedBaseline(@TempDir Path dir) {
        String text = agent(resultOf(List.of(mutant(2, MutantStatus.SURVIVED)),
                List.of("ex.CalcTest#broken")), dir);

        assertTrue(text.contains("baseline-failures: 1"), text);
        assertTrue(text.contains("ex.CalcTest#broken"), text);
        assertTrue(text.indexOf("baseline-failures") < text.indexOf("survived:"),
                "it has to be read before the findings are acted on: " + text);
    }

    /**
     * A hung mutant is detected, so it belongs with the findings rather than in the summary only.
     *
     * <p>It gets its own section rather than being folded in with survivors: nothing is wrong with
     * the test, so the action is different -- the mutant looped, and what to do about it is decide
     * whether that is a real hang or a loop guard that needs raising.
     */
    @Test
    void theAgentReportCountsAndSectionsTimeouts(@TempDir Path dir) {
        String text = agent(resultOf(List.of(
                mutant(1, MutantStatus.KILLED),
                mutant(2, MutantStatus.TIMED_OUT)), List.of()), dir);

        assertTrue(text.contains("1 timed out"), "the headline has to mention them: " + text);
        assertTrue(text.contains("timed-out:"), text);
        assertTrue(text.contains("  2 MATH"), text);
    }

    @Test
    void everyReporterAnswersToItsRegisteredId(@TempDir Path dir) {
        Reporters reporters = new Reporters(new PrintStream(new ByteArrayOutputStream()));
        for (String id : List.of("console", "json", "elements", "html", "annotations", "agent")) {
            assertEquals(id, reporters.byId(id).id(),
                    "a reporter must report the id it was registered under, or --reporters and "
                            + "the report it produces disagree");
        }
    }

    @Test
    void theAgentReportNamesMutantsOutsideTheScore(@TempDir Path dir) {
        String text = agent(resultOf(List.of(
                mutant(1, MutantStatus.KILLED),
                mutant(2, MutantStatus.RUN_ERROR)), List.of()), dir);

        assertTrue(text.contains("1 not scored"),
                "a score over very little must not look like a score over everything: " + text);
    }

    /**
     * The reason this reporter exists, asserted rather than claimed.
     *
     * <p>The ratio grows with the proportion of killed mutants, so a healthy suite -- the case
     * an agent meets most often -- saves the most.
     */
    @Test
    void theAgentReportIsSubstantiallySmallerThanTheJsonOne(@TempDir Path dir) throws Exception {
        List<Mutant> many = new java.util.ArrayList<>();
        for (int line = 1; line <= 100; line++) {
            many.add(mutant(line, line % 10 == 0 ? MutantStatus.SURVIVED : MutantStatus.KILLED));
        }
        AnalysisResult result = resultOf(many, List.of());

        String agentReport = agent(result, dir);
        new NativeJsonReporter().write(result, ReportContext.of(dir, List.of()));
        String json = Files.readString(dir.resolve(NativeJsonReporter.FILE_NAME),
                StandardCharsets.UTF_8);

        assertTrue(agentReport.length() * 10 < json.length(),
                "expected an order of magnitude, got " + agentReport.length() + " vs "
                        + json.length() + " characters");
    }

    private static long count(String text, String needle) {
        return text.split(needle, -1).length - 1L;
    }
}
