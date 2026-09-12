package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.ClassBytes;
import io.github.huyz0.jzap.core.ClassScanner;
import io.github.huyz0.jzap.core.CoverageInstrumenter;
import io.github.huyz0.jzap.core.MinionProcess;
import io.github.huyz0.jzap.core.ProbeIndex;
import io.github.huyz0.jzap.core.RuntimeJars;
import io.github.huyz0.jzap.model.ModuleModel;
import io.github.huyz0.jzap.model.Scope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the controller/minion boundary on its own, so a coverage or protocol problem is
 * distinguishable from an engine problem.
 */
class MinionIntegrationTest {

    @Test
    void gathersPerTestCoverageFromAForkedJvm() {
        ModuleModel module = new Fixture().model(Scope.all()).modules().get(0);
        List<ClassBytes> classes = new ClassScanner(List.of(), List.of()).scan(module.mutableCodePaths());
        assertFalse(classes.isEmpty(), "fixture classes were not found");

        ProbeIndex index = new ProbeIndex();
        CoverageInstrumenter instrumenter = new CoverageInstrumenter(index);
        List<ClassBytes> instrumented = new ArrayList<>();
        for (ClassBytes c : classes) {
            instrumented.add(new ClassBytes(c.binaryName(),
                    instrumenter.instrument(c.binaryName(), c.bytes()), "instrumented"));
        }
        assertTrue(index.size() > 0, "no probes were allocated");

        List<String> hitProbeReport = new ArrayList<>();
        int totalHits = 0;
        String minionOutput;
        try (MinionProcess minion = MinionProcess.start(module, RuntimeJars.discover(), true)) {
            minion.initCoverage(index.size(), instrumented);
            List<String> tests = minion.listTests(module.testClassPaths());
            assertEquals(3, tests.size(), "expected the fixture's three tests, got " + tests);

            for (String test : tests) {
                MinionProcess.TestCoverage coverage = minion.runTestForCoverage(test, 60_000);
                assertTrue(coverage.passed(), "fixture test failed in the minion: " + test
                        + " -- " + coverage.failureMessage());
                totalHits += coverage.probeIds().length;
                hitProbeReport.add(test + " hit " + coverage.probeIds().length + " probes");
            }
            minionOutput = minion.output();
        }

        assertTrue(totalHits > 0,
                "no probes were recorded, so test selection would report everything as uncovered.\n"
                        + String.join("\n", hitProbeReport)
                        + "\nMinion output:\n" + minionOutput);
    }

    @Test
    void probeHitsMapBackToTheClassUnderTest() {
        ModuleModel module = new Fixture().model(Scope.all()).modules().get(0);
        List<ClassBytes> classes = new ClassScanner(List.of(), List.of()).scan(module.mutableCodePaths());
        ProbeIndex index = new ProbeIndex();
        CoverageInstrumenter instrumenter = new CoverageInstrumenter(index);
        List<ClassBytes> instrumented = new ArrayList<>();
        for (ClassBytes c : classes) {
            instrumented.add(new ClassBytes(c.binaryName(),
                    instrumenter.instrument(c.binaryName(), c.bytes()), "instrumented"));
        }

        boolean sawDiscountLine = false;
        try (MinionProcess minion = MinionProcess.start(module, RuntimeJars.discover(), true)) {
            minion.initCoverage(index.size(), instrumented);
            List<String> tests = minion.listTests(module.testClassPaths());
            for (String test : tests) {
                for (int probe : minion.runTestForCoverage(test, 60_000).probeIds()) {
                    for (int line = 1; line < 100; line++) {
                        if (index.lookup("sample.Discount", line) == probe) {
                            sawDiscountLine = true;
                        }
                    }
                }
            }
        }

        assertTrue(sawDiscountLine, "no probe in sample.Discount was hit by any fixture test");
    }
}
