package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.testing.Fixture;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The module graph, asserted rather than assumed.
 *
 * <p>Layering is the kind of property that is easy to establish and easy to lose: one import
 * added to fix something in a hurry, and the engine depends on the command line. Nothing in
 * Gradle prevents it -- a build file will happily grow a dependency -- so the shape is checked
 * here against what each module is allowed to see.
 *
 * <p>Read the table below as the architecture. Everything bottoms out at the model, which knows
 * nothing about anything; the wire and the agent are dependency-free because they are loaded into
 * the JVM running the user's tests; and the engine, git scoping and reporting are siblings that
 * share only the model, which is what lets a CI system feed the engine a patch file instead of a
 * repository and what keeps reports independent of how analysis works.
 */
class ModuleBoundariesTest {

    /** What each module's main sources may import from io.github.huyz0.jzap. */
    private static final Map<String, Set<String>> ALLOWED = new LinkedHashMap<>();

    static {
        // The vocabulary. Depends on nothing: every other module is free to speak it.
        ALLOWED.put("jzap-model", Set.of());
        // The controller/minion protocol, and the agent. Dependency-free on purpose -- both are
        // loaded into the JVM running the user's tests, where anything they dragged in could
        // clash with the project's own dependencies.
        ALLOWED.put("jzap-wire", Set.of());
        ALLOWED.put("jzap-agent", Set.of());
        // The forked JVM: speaks the protocol, uses the agent, and knows nothing of the engine.
        ALLOWED.put("jzap-minion", Set.of("wire", "agent"));
        // The engine. Reaches the agent for its constants only, and never git or reporting.
        ALLOWED.put("jzap-core", Set.of("model", "wire", "agent", "core"));
        // Git scoping and reporting are siblings of the engine, not layers of it.
        ALLOWED.put("jzap-git", Set.of("model", "git"));
        ALLOWED.put("jzap-report", Set.of("model", "report"));
        // The composition root, and the only module allowed to know about all of them.
        ALLOWED.put("jzap-cli", Set.of("model", "wire", "core", "git", "report", "cli"));
        // Both build-tool adapters fork the CLI rather than linking it, so neither sees any of
        // this. That is what lets the engine's version move independently of the plugin's, and
        // keeps ASM off a buildscript classpath.
        ALLOWED.put("jzap-gradle", Set.of());
        ALLOWED.put("jzap-maven", Set.of());
    }

    private static final Pattern IMPORT =
            Pattern.compile("^import (?:static )?dev\\.jzap\\.([a-z]+)\\.", Pattern.MULTILINE);

    private static Path repositoryRoot() {
        return new Fixture().projectRoot();
    }

    @Test
    void everyModuleImportsOnlyWhatItIsAllowedTo() {
        List<String> violations = new ArrayList<>();

        ALLOWED.forEach((module, allowed) -> {
            Path main = repositoryRoot().resolve(module).resolve("src/main/java");
            List<Path> sources = Files.isDirectory(main) ? javaFilesIn(main) : List.of();
            if (sources.isEmpty()) {
                violations.add(module + " has no main sources; is the table stale?");
                return;
            }
            for (Path file : sources) {
                Matcher matcher = IMPORT.matcher(read(file));
                while (matcher.find()) {
                    String imported = matcher.group(1);
                    if (!allowed.contains(imported)) {
                        violations.add(module + " imports io.github.huyz0.jzap." + imported
                                + " (" + main.relativize(file) + "), which it may not see");
                    }
                }
            }
        });

        assertTrue(violations.isEmpty(),
                "the module graph has changed:\n  " + String.join("\n  ", violations));
    }

    @Test
    void theTableCoversEveryModuleThatExists() {
        Set<String> onDisk = new LinkedHashSet<>();
        try (Stream<Path> entries = Files.list(repositoryRoot())) {
            entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("jzap-"))
                    // Modules with real production code. A leftover empty source directory is
                    // not a module, and the check should not depend on local cruft.
                    .filter(name -> {
                        Path main = repositoryRoot().resolve(name).resolve("src/main/java");
                        return Files.isDirectory(main) && !javaFilesIn(main).isEmpty();
                    })
                    .forEach(onDisk::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertEquals(ALLOWED.keySet(), onDisk,
                "a module was added or removed without saying what it may depend on. The table "
                        + "in this test is the architecture; update it deliberately.");
    }

    /**
     * The graph is a DAG, checked from the table rather than trusted.
     *
     * <p>Gradle does reject a circular project dependency, but only for what the build files
     * declare. This closes the same loop over what the source actually imports.
     */
    @Test
    void theAllowedGraphHasNoCycles() {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        ALLOWED.forEach((module, allowed) -> {
            Set<String> out = new LinkedHashSet<>();
            for (String other : ALLOWED.keySet()) {
                String pkg = other.substring("jzap-".length());
                if (!other.equals(module) && allowed.contains(pkg)) {
                    out.add(other);
                }
            }
            edges.put(module, out);
        });

        Set<String> settled = new LinkedHashSet<>();
        for (String module : edges.keySet()) {
            visit(module, edges, new LinkedHashSet<>(), settled);
        }
    }

    private static void visit(String module, Map<String, Set<String>> edges,
                              Set<String> onPath, Set<String> settled) {
        if (settled.contains(module)) {
            return;
        }
        if (!onPath.add(module)) {
            fail("dependency cycle: " + String.join(" -> ", onPath) + " -> " + module);
        }
        for (String next : edges.getOrDefault(module, Set.of())) {
            visit(next, edges, onPath, settled);
        }
        onPath.remove(module);
        settled.add(module);
    }

    private static List<Path> javaFilesIn(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + root, e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
