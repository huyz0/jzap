package io.github.huyz0.jzap.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps the synthetic line numbers Kotlin gives inlined code back to the source they came from.
 *
 * <p>When kotlinc inlines a function it copies the body into the caller and gives the copy line
 * numbers past the end of the file — one distinct range per call site. Without translating them,
 * every mutant in an inlined body is reported against a source line that does not exist. The
 * translation table is the {@code SourceDebugExtension} attribute, in JSR-045 SMAP format:
 *
 * <pre>
 * SMAP
 * Pricing.kt
 * Kotlin
 * *S Kotlin
 * *F
 * + 1 Pricing.kt
 * ksample/PricingKt
 * *L
 * 1#1,50:1        input lines 1..50 of file 1 land at output lines 1..50
 * 40#1,2:51       input lines 40..41 land at output lines 51..52
 * 40#1,2:53       and again, for the second call site, at 53..54
 * *E
 * </pre>
 *
 * <p>Only the {@code *S Kotlin} stratum is read. The {@code KotlinDebug} stratum that follows maps
 * the same output lines to the <em>call sites</em> instead, which is useful for a debugger
 * stepping through and wrong for a report: a mutant in an inlined body is a mutation of the
 * inline function's source, not of the line that called it.
 */
final class SourceMap {

    /**
     * Most output lines one {@code *L} entry may declare. A real entry covers at most the lines of
     * one file, so this is orders of magnitude above anything kotlinc emits.
     */
    private static final int MAX_MAPPINGS_PER_RANGE = 1_000_000;

    private final Map<Integer, Integer> outputToInput;
    private final Map<Integer, String> fileNames;
    private final Map<Integer, Integer> outputToFile;

    private SourceMap(Map<Integer, Integer> outputToInput, Map<Integer, String> fileNames,
                      Map<Integer, Integer> outputToFile) {
        this.outputToInput = outputToInput;
        this.fileNames = fileNames;
        this.outputToFile = outputToFile;
    }

    public static final SourceMap EMPTY =
            new SourceMap(Map.of(), Map.of(), Map.of());

    public boolean isEmpty() {
        return outputToInput.isEmpty();
    }

    /** The real source line for a possibly-synthetic one, or the input unchanged. */
    public int toSourceLine(int line) {
        return outputToInput.getOrDefault(line, line);
    }

    /** The file a line came from, when the map names one different from the class's own. */
    public Optional<String> toSourceFile(int line) {
        Integer fileId = outputToFile.get(line);
        return fileId == null ? Optional.empty() : Optional.ofNullable(fileNames.get(fileId));
    }

    /** Whether this line is a copy of code that lives somewhere else. */
    public boolean isInlined(int line) {
        Integer source = outputToInput.get(line);
        return source != null && source != line;
    }

    /**
     * Parses a {@code SourceDebugExtension}. Anything unrecognised yields an empty map rather
     * than an error: a debug attribute is a hint, and failing a whole analysis over a malformed
     * one would be a poor trade.
     */
    public static SourceMap parse(String debug) {
        if (debug == null || !debug.startsWith("SMAP")) {
            return EMPTY;
        }
        String[] lines = debug.split("\n");
        Map<Integer, String> files = new HashMap<>();
        Map<Integer, Integer> outputToInput = new HashMap<>();
        Map<Integer, Integer> outputToFile = new HashMap<>();

        String section = null;
        boolean inKotlinStratum = true;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("*S ")) {
                // Only the first stratum is the one that maps to the originating source.
                inKotlinStratum = section == null;
                section = null;
                continue;
            }
            if (line.equals("*F") || line.equals("*L") || line.equals("*E")) {
                section = line;
                continue;
            }
            if (!inKotlinStratum || section == null) {
                continue;
            }
            if (section.equals("*F")) {
                // "+ 1 Pricing.kt" followed by a path line, or plain "1 Pricing.kt".
                String entry = line.startsWith("+ ") ? line.substring(2) : line;
                int space = entry.indexOf(' ');
                if (space > 0) {
                    try {
                        files.put(Integer.parseInt(entry.substring(0, space).trim()),
                                entry.substring(space + 1).trim());
                    } catch (NumberFormatException ignored) {
                        // a path continuation line, which carries no id of its own
                    }
                }
            } else if (section.equals("*L")) {
                parseLineMapping(line, outputToInput, outputToFile);
            }
        }
        return outputToInput.isEmpty()
                ? EMPTY
                : new SourceMap(outputToInput, files, outputToFile);
    }

    /** {@code inputStart#fileId,repeatCount:outputStart,outputIncrement} */
    private static void parseLineMapping(String line, Map<Integer, Integer> outputToInput,
                                         Map<Integer, Integer> outputToFile) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return;
        }
        String input = line.substring(0, colon);
        String output = line.substring(colon + 1);

        int fileId = 1;
        int repeatCount = 1;
        int hash = input.indexOf('#');
        if (hash >= 0) {
            String rest = input.substring(hash + 1);
            input = input.substring(0, hash);
            int comma = rest.indexOf(',');
            if (comma >= 0) {
                fileId = parse(rest.substring(0, comma), 1);
                repeatCount = parse(rest.substring(comma + 1), 1);
            } else {
                fileId = parse(rest, 1);
            }
        }
        int inputStart = parse(input, -1);

        int outputIncrement = 1;
        int comma = output.indexOf(',');
        if (comma >= 0) {
            outputIncrement = parse(output.substring(comma + 1), 1);
            output = output.substring(0, comma);
        }
        int outputStart = parse(output, -1);
        if (inputStart < 0 || outputStart < 0) {
            return;
        }

        // Bounded because the counts come from an attribute nothing validates. ClassReader treats
        // SourceDebugExtension as an opaque string, so a corrupt one can declare a repeat count of
        // two billion, and the loop below would then fill a map until the heap ran out -- during
        // discovery, with no indication of which class was responsible.
        int span = Math.max(1, outputIncrement);
        if ((long) repeatCount * span > MAX_MAPPINGS_PER_RANGE) {
            return;
        }
        for (int i = 0; i < repeatCount; i++) {
            for (int j = 0; j < span; j++) {
                int outputLine = outputStart + i * span + j;
                // First mapping wins: later call sites repeat the same input lines, and the
                // output ranges do not overlap, so this only guards against malformed input.
                outputToInput.putIfAbsent(outputLine, inputStart + i);
                outputToFile.putIfAbsent(outputLine, fileId);
            }
        }
    }

    private static int parse(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
