package io.github.huyz0.jzap.report;

import io.github.huyz0.jzap.model.Mutant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Finds the source file behind a mutant, so reports can show the code that changed. */
public final class SourceLocator {

    private final List<Path> sourceRoots;
    private final Map<String, Optional<Path>> cache = new HashMap<>();

    public SourceLocator(List<Path> sourceRoots) {
        this.sourceRoots = List.copyOf(sourceRoots);
    }

    /**
     * Path relative to a source root, e.g. {@code ex/Calc.java}.
     *
     * <p>The rule itself lives on {@link Mutant}, because diff scoping in the engine matches
     * against the same answer and a second copy here could drift from it.
     */
    public static String relativePath(Mutant mutant) {
        return mutant.sourcePath();
    }

    public Optional<Path> locate(Mutant mutant) {
        return cache.computeIfAbsent(relativePath(mutant), relative -> {
            for (Path root : sourceRoots) {
                Path candidate = root.resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        });
    }

    public Optional<String> read(Mutant mutant) {
        return locate(mutant).flatMap(p -> {
            try {
                return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }

    /** The single source line a mutant sits on, trimmed, if it can be read. */
    public Optional<String> line(Mutant mutant) {
        return locate(mutant).flatMap(p -> {
            try {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                int index = mutant.key().line() - 1;
                return index >= 0 && index < lines.size()
                        ? Optional.of(lines.get(index).trim())
                        : Optional.empty();
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }
}
