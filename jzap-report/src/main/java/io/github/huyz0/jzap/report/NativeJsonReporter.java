package io.github.huyz0.jzap.report;

import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * jzap's own full-detail format. This is what the parity harness reads, so its shape is part
 * of the tool's contract rather than a convenience.
 *
 * <p>Deterministic by construction: mutants arrive in stable key order and timings are written
 * to a separate object that comparison tooling ignores.
 */
public final class NativeJsonReporter implements Reporter {

    public static final String FILE_NAME = "jzap-result.json";

    @Override
    public String id() {
        return "json";
    }

    @Override
    public void write(AnalysisResult result, ReportContext context) {
        Json json = new Json();
        json.startObject();
        json.key("engine").value(result.engine());
        json.key("scope").value(result.scopeSummary());
        json.key("testsDiscovered").value(result.testsDiscovered());
        json.key("mutationScore").value(result.mutationScore());
        json.key("testStrength").value(result.testStrength());
        // The ratios above are over these, not over every mutant in the list below, so a consumer
        // that wants to recompute or aggregate them needs the denominators as well.
        json.key("scoredMutants").value(result.scored());
        json.key("unscoredMutants").value(result.unscored());
        json.key("coveredMutants").value(result.covered());
        json.key("detectedMutants").value(result.detected());

        json.key("failingBaselineTests").startArray();
        for (String test : result.failingBaselineTests()) {
            json.element().value(test);
        }
        json.endArray();

        json.key("mutants").startArray();
        for (Mutant m : result.mutants()) {
            json.element().startObject();
            json.key("key").value(m.key().asString());
            json.key("class").value(m.key().className());
            json.key("method").value(m.key().methodName() + m.key().descriptor());
            json.key("line").value(m.key().line());
            json.key("mutator").value(m.key().mutator());
            json.key("ordinal").value(m.key().ordinal());
            json.key("sourceFile").value(SourceLocator.relativePath(m));
            json.key("description").value(m.description());
            json.key("status").value(m.status() == null ? null : m.status().name());
            json.key("killingTest").value(m.killingTest());
            json.key("coveringTests").value(m.coveringTests());
            json.key("testsRun").value(m.testsRun());
            json.endObject();
        }
        json.endArray();

        json.key("timings").startObject();
        result.timings().forEach((phase, millis) -> json.key(phase).value(millis));
        json.endObject();
        json.endObject();

        Path out = context.outputDir().resolve(FILE_NAME);
        try {
            Files.createDirectories(context.outputDir());
            Files.writeString(out, json.finish() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + out, e);
        }
    }
}
