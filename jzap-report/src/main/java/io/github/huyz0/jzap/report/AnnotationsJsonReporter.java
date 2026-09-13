package io.github.huyz0.jzap.report;

import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * One annotation per surviving mutant, for posting as pull-request review comments.
 *
 * <p>This is jzap's own format, not a reimplementation of arcmutate's {@code gitci} output:
 * that format is theirs and undocumented here, so claiming compatibility would be a promise
 * this file cannot keep. The shape below is what the CI integrations of M13 consume.
 */
public final class AnnotationsJsonReporter implements Reporter {

    public static final String FILE_NAME = "jzap-annotations.json";

    private final String level;

    public AnnotationsJsonReporter() {
        this("warning");
    }

    /** @param level {@code error}, {@code warning} or {@code notice} */
    public AnnotationsJsonReporter(String level) {
        this.level = level;
    }

    @Override
    public String id() {
        return "annotations";
    }

    @Override
    public void write(AnalysisResult result, ReportContext context) {
        SourceLocator sources = new SourceLocator(context.sourceRoots());
        Json json = new Json();
        json.startObject();
        json.key("tool").value("jzap");
        json.key("level").value(level);
        json.key("summary").value(String.format(Locale.ROOT,
                "%d of %d mutants survived; mutation score %.1f%%",
                result.count(MutantStatus.SURVIVED), result.mutants().size(), result.mutationScore()));
        json.key("annotations").startArray();
        for (Mutant m : result.mutants()) {
            // Only survivors are actionable. A killed mutant needs no comment, and commenting
            // on uncovered code in a pull request review is a different conversation.
            if (m.status() != MutantStatus.SURVIVED) {
                continue;
            }
            json.element().startObject();
            json.key("path").value(SourceLocator.relativePath(m));
            json.key("line").value(m.key().line());
            json.key("level").value(level);
            json.key("mutator").value(m.key().mutator());
            json.key("mutantKey").value(m.key().asString());
            json.key("title").value("Surviving mutant: " + m.key().mutator());
            json.key("message").value(m.description()
                    + " -- no test failed with this change applied.");
            json.key("code").value(sources.line(m).orElse(null));
            json.endObject();
        }
        json.endArray();
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
