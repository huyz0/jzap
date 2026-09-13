package io.github.huyz0.jzap.git;

import io.github.huyz0.jzap.model.ChangedLines;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a unified diff into {@link ChangedLines}, with no git repository required.
 *
 * <p>This is the path for CI systems that hand over a patch rather than a checkout, and it is
 * also how the engine's diff scoping is tested without constructing a repository.
 */
public final class PatchScope {

    /** {@code @@ -oldStart,oldCount +newStart,newCount @@}, either count omitted meaning one. */
    private static final Pattern HUNK =
            Pattern.compile("^@@ -\\d+(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    private PatchScope() {
    }

    public static ChangedLines fromFile(Path patch) {
        try {
            return parse(Files.readAllLines(patch, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read patch file " + patch, e);
        }
    }

    /**
     * Reads added line positions out of a unified diff.
     *
     * <p>Hunk boundaries are tracked from the counts in each hunk header rather than guessed from
     * line prefixes, because inside a hunk every line is content and content can look like
     * anything. An added line reading {@code ++ i;} appears in the patch as {@code +++ i;}; taken
     * for a file header it silently redirects the rest of the file's changes onto a path that does
     * not exist, and those changed lines are then never analysed at all.
     */
    public static ChangedLines parse(List<String> lines) {
        ChangedLines changed = ChangedLines.empty();
        String currentPath = null;
        int newLineNumber = 0;
        int oldRemaining = 0;
        int newRemaining = 0;

        for (String line : lines) {
            if (oldRemaining <= 0 && newRemaining <= 0) {
                // Between hunks, where headers are the only lines that mean anything.
                if (line.startsWith("+++ ")) {
                    currentPath = stripPrefix(line.substring(4).trim());
                    continue;
                }
                Matcher hunk = HUNK.matcher(line);
                if (hunk.matches()) {
                    newLineNumber = Integer.parseInt(hunk.group(2));
                    oldRemaining = countOf(hunk.group(1));
                    newRemaining = countOf(hunk.group(3));
                }
                continue;
            }
            if (currentPath == null) {
                continue;
            }
            if (line.startsWith("\\")) {
                // "\ No newline at end of file" describes the line before it; it is not a line of
                // either file and must not consume either count.
                continue;
            }
            if (line.startsWith("+")) {
                changed.add(currentPath, newLineNumber++);
                newRemaining--;
            } else if (line.startsWith("-")) {
                // A removed line occupies no position in the new file, so it advances nothing.
                // Mutants cannot be seeded into code that no longer exists.
                oldRemaining--;
            } else {
                // Context: present in both files. The standard form is a leading space, and a
                // bare empty line is what stripping trailing whitespace leaves of a blank context
                // line. Treating that as anything but context would shift every line number after
                // it in the hunk.
                newLineNumber++;
                newRemaining--;
                oldRemaining--;
            }
        }
        return changed;
    }

    /** A hunk header count, which is omitted when the hunk covers a single line. */
    private static int countOf(String group) {
        return group == null ? 1 : Integer.parseInt(group);
    }

    /** Removes the {@code a/} or {@code b/} prefix git puts on diff paths. */
    private static String stripPrefix(String path) {
        String cleaned = path;
        int tab = cleaned.indexOf('\t');
        if (tab >= 0) {
            cleaned = cleaned.substring(0, tab);
        }
        if (cleaned.startsWith("a/") || cleaned.startsWith("b/")) {
            return cleaned.substring(2);
        }
        return cleaned;
    }
}
