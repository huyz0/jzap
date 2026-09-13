package io.github.huyz0.jzap.report;

import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mutation-testing-elements report schema, as used by Stryker's viewer and the dashboards
 * built on it.
 *
 * <p>Emitting a format that already has tooling is worth more than inventing one: an existing
 * team can point their current dashboard at jzap without writing anything.
 */
final class ElementsJsonReporter implements Reporter {

    public static final String FILE_NAME = "mutation-test-elements.json";

    private static final String SCHEMA =
            "https://raw.githubusercontent.com/stryker-mutator/mutation-testing-elements/master/"
                    + "packages/report-schema/src/mutation-testing-report-schema.json";

    @Override
    public String id() {
        return "elements";
    }

    /** Maps jzap statuses onto the schema's vocabulary. */
    static String schemaStatus(MutantStatus status) {
        if (status == null) {
            return "Ignored";
        }
        return switch (status) {
            case KILLED -> "Killed";
            case SURVIVED -> "Survived";
            case NO_COVERAGE -> "NoCoverage";
            case TIMED_OUT -> "Timeout";
            // A mutant that will not verify is closest to a compile error: it is not a
            // statement about the test suite at all.
            case NON_VIABLE -> "CompileError";
            case RUN_ERROR -> "RuntimeError";
        };
    }

    @Override
    public void write(AnalysisResult result, ReportContext context) {
        SourceLocator sources = new SourceLocator(context.sourceRoots());
        Map<String, List<Mutant>> byFile = new LinkedHashMap<>();
        Map<String, Mutant> exemplarByFile = new LinkedHashMap<>();
        for (Mutant m : result.mutants()) {
            String path = SourceLocator.relativePath(m);
            byFile.computeIfAbsent(path, k -> new ArrayList<>()).add(m);
            exemplarByFile.putIfAbsent(path, m);
        }

        Json json = new Json();
        json.startObject();
        json.key("$schema").value(SCHEMA);
        json.key("schemaVersion").value("1.0");
        json.key("thresholds").startObject();
        json.key("high").value(context.highThreshold());
        json.key("low").value(context.lowThreshold());
        json.endObject();
        json.key("files").startObject();

        int id = 0;
        for (Map.Entry<String, List<Mutant>> entry : byFile.entrySet()) {
            json.key(entry.getKey()).startObject();
            json.key("language").value(languageOf(entry.getKey()));
            json.key("source").value(sources.read(exemplarByFile.get(entry.getKey())).orElse(""));
            json.key("mutants").startArray();
            for (Mutant m : entry.getValue()) {
                json.element().startObject();
                json.key("id").value(Integer.toString(++id));
                json.key("mutatorName").value(m.key().mutator());
                json.key("description").value(m.description());
                json.key("status").value(schemaStatus(m.status()));
                json.key("location").startObject();
                json.key("start").startObject();
                json.key("line").value(Math.max(1, m.key().line()));
                json.key("column").value(1);
                json.endObject();
                json.key("end").startObject();
                json.key("line").value(Math.max(1, m.key().line()));
                json.key("column").value(1);
                json.endObject();
                json.endObject();
                json.endObject();
            }
            json.endArray();
            json.endObject();
        }

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

    private static String languageOf(String path) {
        return path.endsWith(".kt") ? "kotlin" : "java";
    }
}
