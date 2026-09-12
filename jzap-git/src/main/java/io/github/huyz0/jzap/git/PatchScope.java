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

    private static final Pattern HUNK =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@.*");

    private PatchScope() {
    }

    public static ChangedLines fromFile(Path patch) {
        try {
            return parse(Files.readAllLines(patch, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read patch file " + patch, e);
        }
    }

    public static ChangedLines parse(List<String> lines) {
        ChangedLines changed = ChangedLines.empty();
        String currentPath = null;
        int newLineNumber = 0;

        for (String line : lines) {
            if (line.startsWith("+++ ")) {
                currentPath = stripPrefix(line.substring(4).trim());
                continue;
            }
            if (line.startsWith("--- ")) {
                continue;
            }
            Matcher hunk = HUNK.matcher(line);
            if (hunk.matches()) {
                newLineNumber = Integer.parseInt(hunk.group(1));
                continue;
            }
            if (currentPath == null || newLineNumber == 0) {
                continue;
            }
            if (line.startsWith("+")) {
                changed.add(currentPath, newLineNumber);
                newLineNumber++;
            } else if (line.startsWith("-")) {
                // A removed line occupies no position in the new file, so it advances nothing.
                // Mutants cannot be seeded into code that no longer exists.
                continue;
            } else if (line.startsWith(" ")) {
                newLineNumber++;
            }
        }
        return changed;
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
