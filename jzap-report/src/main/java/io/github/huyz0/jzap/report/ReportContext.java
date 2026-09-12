package io.github.huyz0.jzap.report;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything a reporter needs beyond the result itself.
 *
 * @param outputDir     directory reports are written to
 * @param sourceRoots   source directories, so reports can show the mutated line
 * @param repositoryRoot base for rendering paths; may be null
 * @param highThreshold score at or above which a report reads as healthy
 * @param lowThreshold  score below which a report reads as poor
 */
public record ReportContext(
        Path outputDir,
        List<Path> sourceRoots,
        Path repositoryRoot,
        int highThreshold,
        int lowThreshold) {

    public ReportContext {
        sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
        highThreshold = highThreshold <= 0 ? 80 : highThreshold;
        lowThreshold = lowThreshold <= 0 ? 60 : lowThreshold;
    }

    public static ReportContext of(Path outputDir, List<Path> sourceRoots) {
        return new ReportContext(outputDir, sourceRoots, null, 80, 60);
    }
}
