package io.github.huyz0.jzap.report;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything a reporter needs beyond the result itself.
 *
 * @param outputDir     directory reports are written to
 * @param sourceRoots   source directories, so reports can show the mutated line
 * @param repositoryRoot base for rendering paths; may be null
 * @param highThreshold score at or above which a report reads as healthy; defaults to
 *                      {@link #DEFAULT_HIGH_THRESHOLD} when not positive
 * @param lowThreshold  score below which a report reads as poor; defaults to
 *                      {@link #DEFAULT_LOW_THRESHOLD} when not positive
 */
public record ReportContext(
        Path outputDir,
        List<Path> sourceRoots,
        Path repositoryRoot,
        int highThreshold,
        int lowThreshold) {

    public static final int DEFAULT_HIGH_THRESHOLD = 80;
    public static final int DEFAULT_LOW_THRESHOLD = 60;

    public ReportContext {
        sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
        highThreshold = highThreshold <= 0 ? DEFAULT_HIGH_THRESHOLD : highThreshold;
        lowThreshold = lowThreshold <= 0 ? DEFAULT_LOW_THRESHOLD : lowThreshold;
    }

    /** Reports written to {@code outputDir}, with the default thresholds and no repository root. */
    public static ReportContext of(Path outputDir, List<Path> sourceRoots) {
        return new ReportContext(outputDir, sourceRoots, null,
                DEFAULT_HIGH_THRESHOLD, DEFAULT_LOW_THRESHOLD);
    }
}
