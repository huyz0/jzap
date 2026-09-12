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
import java.util.Locale;
import java.util.Map;

/** A single self-contained HTML file, grouped by source file and ordered by survivors first. */
public final class HtmlReporter implements Reporter {

    public static final String FILE_NAME = "index.html";

    @Override
    public String id() {
        return "html";
    }

    @Override
    public void write(AnalysisResult result, ReportContext context) {
        SourceLocator sources = new SourceLocator(context.sourceRoots());
        Map<String, List<Mutant>> byFile = new LinkedHashMap<>();
        for (Mutant m : result.mutants()) {
            byFile.computeIfAbsent(SourceLocator.relativePath(m), k -> new ArrayList<>()).add(m);
        }

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<title>jzap mutation report</title>\n<style>\n")
                .append("""
                        :root { color-scheme: light dark; --bg:#fff; --fg:#111; --muted:#666;
                          --line:#e3e3e3; --killed:#1a7f37; --survived:#b3261e; --uncovered:#8a6d00; }
                        @media (prefers-color-scheme: dark) {
                          :root { --bg:#14171a; --fg:#e8e8e8; --muted:#9aa0a6; --line:#2c3136;
                            --killed:#4ac26b; --survived:#ff7b72; --uncovered:#d4a72c; }
                        }
                        body { margin:0; padding:2rem; background:var(--bg); color:var(--fg);
                          font:14px/1.5 ui-sans-serif, system-ui, sans-serif; }
                        h1 { font-size:1.3rem; margin:0 0 .25rem; }
                        .scope { color:var(--muted); margin-bottom:1.5rem; }
                        .totals { display:flex; gap:1.5rem; flex-wrap:wrap; margin-bottom:2rem; }
                        .totals div { min-width:6rem; }
                        .totals b { display:block; font-size:1.5rem; font-weight:600; }
                        .file { border:1px solid var(--line); border-radius:8px; margin-bottom:1rem;
                          overflow:hidden; }
                        .file > h2 { font-size:.95rem; margin:0; padding:.6rem .9rem;
                          border-bottom:1px solid var(--line); font-family:ui-monospace, monospace; }
                        table { border-collapse:collapse; width:100%; }
                        td { padding:.35rem .9rem; border-top:1px solid var(--line);
                          vertical-align:top; }
                        td.line { text-align:right; color:var(--muted); width:4rem;
                          font-family:ui-monospace, monospace; }
                        td.status { width:7rem; font-weight:600; }
                        code { font-family:ui-monospace, monospace; color:var(--muted); }
                        .KILLED { color:var(--killed); }
                        .SURVIVED { color:var(--survived); }
                        .NO_COVERAGE { color:var(--uncovered); }
                        .warn { border:1px solid var(--survived); border-radius:8px;
                          padding:.9rem; margin-bottom:1.5rem; }
                        """)
                .append("</style>\n</head>\n<body>\n");

        html.append("<h1>jzap mutation report</h1>\n");
        html.append("<p class=\"scope\">").append(escape(result.scopeSummary())).append("</p>\n");

        if (!result.failingBaselineTests().isEmpty()) {
            html.append("<div class=\"warn\"><b>")
                    .append(result.failingBaselineTests().size())
                    .append(" test(s) already fail without any mutant applied.</b> They were excluded")
                    .append(" from selection; fix them before trusting the verdicts below.<ul>");
            for (String t : result.failingBaselineTests()) {
                html.append("<li><code>").append(escape(t)).append("</code></li>");
            }
            html.append("</ul></div>\n");
        }

        html.append("<div class=\"totals\">")
                .append(total("score", String.format(Locale.ROOT, "%.1f%%", result.mutationScore())))
                .append(total("test strength", String.format(Locale.ROOT, "%.1f%%", result.testStrength())))
                .append(total("killed", result.count(MutantStatus.KILLED)))
                .append(total("survived", result.count(MutantStatus.SURVIVED)))
                .append(total("no coverage", result.count(MutantStatus.NO_COVERAGE)))
                .append(total("total", result.mutants().size()))
                .append("</div>\n");

        byFile.forEach((file, mutants) -> {
            mutants.sort((a, b) -> {
                int byStatus = Integer.compare(rank(a.status()), rank(b.status()));
                return byStatus != 0 ? byStatus : Integer.compare(a.key().line(), b.key().line());
            });
            html.append("<section class=\"file\"><h2>").append(escape(file)).append("</h2><table>\n");
            for (Mutant m : mutants) {
                html.append("<tr><td class=\"line\">").append(m.key().line()).append("</td>")
                        .append("<td class=\"status ").append(m.status()).append("\">")
                        .append(m.status()).append("</td><td>")
                        .append(escape(m.description()))
                        .append(" <code>[").append(m.key().mutator()).append("]</code>");
                sources.line(m).ifPresent(code ->
                        html.append("<br><code>").append(escape(code)).append("</code>"));
                html.append("</td></tr>\n");
            }
            html.append("</table></section>\n");
        });

        html.append("</body>\n</html>\n");

        Path out = context.outputDir().resolve(FILE_NAME);
        try {
            Files.createDirectories(context.outputDir());
            Files.writeString(out, html.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + out, e);
        }
    }

    /** Survivors first: they are the only rows that ask the reader to do something. */
    private static int rank(MutantStatus status) {
        if (status == MutantStatus.SURVIVED) {
            return 0;
        }
        if (status == MutantStatus.NO_COVERAGE) {
            return 1;
        }
        return 2;
    }

    private static String total(String label, Object value) {
        return "<div><b>" + value + "</b>" + label + "</div>";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
