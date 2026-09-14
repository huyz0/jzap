package io.github.huyz0.jzap.cli;

import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;
import io.github.huyz0.jzap.model.MutantStatus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a finished analysis turns into for CI.
 *
 * <p>These verdicts are driven straight into {@link RunCommand#exitCode} rather than produced by
 * an analysis, because a {@code RUN_ERROR} is by definition something going wrong inside jzap and
 * there is no supported way to ask for one. The exit code is the contract regardless of how the
 * status arose.
 */
class ExitCodeTest {

    private static AnalysisResult resultOf(MutantStatus... statuses) {
        List<Mutant> mutants = new ArrayList<>();
        int line = 1;
        for (MutantStatus status : statuses) {
            mutants.add(new Mutant(new MutantKey("ex.Calc", "add", "(II)I", line++, "MATH", 0),
                    ":app", "Calc.java", "replaced addition with subtraction",
                    status, null, 1, 1, 1L));
        }
        return new AnalysisResult(mutants, Map.of(), 1, "scope", "naive", List.of(), 0);
    }

    /** Runs exitCode with System.err captured, since the reason is half of the contract. */
    private record Outcome(int code, String err) {
    }

    private static Outcome exitCode(RunCommand command, AnalysisResult result) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream was = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            return new Outcome(command.exitCode(result), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(was);
        }
    }

    @Test
    void aRunErrorFailsTheRunEvenThoughItIsOutsideTheScore() {
        Outcome outcome = exitCode(new RunCommand(),
                resultOf(MutantStatus.KILLED, MutantStatus.RUN_ERROR));

        assertEquals(RunCommand.EXIT_FAILED, outcome.code(), outcome.err());
        assertTrue(outcome.err().contains("analysis failed for 1 mutant(s)"), outcome.err());
        assertTrue(outcome.err().contains("not the score for this scope"),
                "the message has to say the score is over the rest: " + outcome.err());
    }

    /**
     * The case the exclusion would otherwise have created.
     *
     * <p>Leaving a RUN_ERROR out of the ratio means the surviving mutants can score 100% over
     * whatever did work. If the exit code only looked at the threshold, an analysis that broke on
     * most of the scope would hand CI a green build and a perfect number.
     */
    @Test
    void aRunErrorBeatsAThresholdThatTheRemainingMutantsMet() {
        RunCommand command = new RunCommand();
        command.threshold = 100.0;

        Outcome outcome = exitCode(command, resultOf(MutantStatus.KILLED, MutantStatus.RUN_ERROR));

        assertEquals(100.0, resultOf(MutantStatus.KILLED, MutantStatus.RUN_ERROR).mutationScore(),
                0.05, "the score over the mutants that were judged is perfect");
        assertEquals(RunCommand.EXIT_FAILED, outcome.code(),
                "and the run still must not pass: " + outcome.err());
    }

    @Test
    void aNonViableMutantDoesNotFailTheRun() {
        RunCommand command = new RunCommand();
        command.threshold = 100.0;

        Outcome outcome = exitCode(command,
                resultOf(MutantStatus.KILLED, MutantStatus.NON_VIABLE));

        assertEquals(RunCommand.EXIT_OK, outcome.code(),
                "a mutant the verifier rejects is an ordinary outcome of mutating bytecode: "
                        + outcome.err());
    }

    @Test
    void aThresholdStillFailsWithItsOwnCodeWhenNothingBroke() {
        RunCommand command = new RunCommand();
        command.threshold = 100.0;

        Outcome outcome = exitCode(command, resultOf(MutantStatus.KILLED, MutantStatus.SURVIVED));

        assertEquals(RunCommand.EXIT_THRESHOLD, outcome.code(), outcome.err());
        assertTrue(outcome.err().contains("below the threshold"), outcome.err());
    }

    @Test
    void aCleanRunPasses() {
        Outcome outcome = exitCode(new RunCommand(),
                resultOf(MutantStatus.KILLED, MutantStatus.SURVIVED));

        assertEquals(RunCommand.EXIT_OK, outcome.code(), outcome.err());
    }
}
