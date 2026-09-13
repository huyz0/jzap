package io.github.huyz0.jzap.git;

import io.github.huyz0.jzap.model.Scope;
import io.github.huyz0.jzap.model.ScopeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What diff scoping says when it cannot do its job.
 *
 * <p>Every one of these is something a CI configuration gets wrong: a shallow clone with no
 * merge base, a build running outside the checkout, a ref that exists on the developer's machine
 * and not on the agent. The scope silently collapsing to nothing would be the worst outcome --
 * a green build that analysed no mutants at all -- so each has to fail and say what to do.
 */
class GitScopeErrorsTest {

    private static Scope diff(String from, String to) {
        return new Scope(ScopeKind.DIFF, from, to, "line", null,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void aDirectoryThatIsNotInARepositorySaysToUseAPatchInstead(@TempDir Path dir) throws Exception {
        // A temp directory has no .git above it, which is also what a source tarball looks like.
        Path outside = Files.createDirectories(dir.resolve("not-a-checkout"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GitScope(outside));

        assertTrue(e.getMessage().contains("no git repository found"), e.getMessage());
        assertTrue(e.getMessage().contains("patch file"),
                "there is a way to scope without git, and this is where to mention it: "
                        + e.getMessage());
    }

    @Test
    void aRefThatDoesNotExistNamesTheAlternatives() throws Exception {
        // This repository is a checkout, so the failure is about the ref rather than the tree.
        try (GitScope scope = new GitScope(Path.of("").toAbsolutePath())) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> scope.resolve(diff("no-such-ref-anywhere", Scope.LOCAL)));

            assertTrue(e.getMessage().contains("cannot resolve git ref"), e.getMessage());
            assertTrue(e.getMessage().contains(Scope.LOCAL), e.getMessage());
            assertTrue(e.getMessage().contains(Scope.EMPTY_TREE),
                    "the empty tree is how a first commit is scoped, and nobody guesses its name: "
                            + e.getMessage());
        }
    }

    @Test
    void anUnsetBaseDefaultsToHead() throws Exception {
        try (GitScope scope = new GitScope(Path.of("").toAbsolutePath())) {
            // Resolving at all is the assertion: a null base must not reach git as a null ref.
            scope.resolve(diff(null, Scope.LOCAL));
            scope.resolve(diff("  ", Scope.LOCAL));
        }
    }

    @Test
    void anUnsetTipMeansUncommittedWork() throws Exception {
        try (GitScope scope = new GitScope(Path.of("").toAbsolutePath())) {
            scope.resolve(diff("HEAD", null));
            scope.resolve(diff("HEAD", "  "));
        }
    }

    @Test
    void theWorkTreeIsWhatDiffPathsAreRelativeTo() throws Exception {
        try (GitScope scope = new GitScope(Path.of("").toAbsolutePath())) {
            assertTrue(Files.isDirectory(scope.workTree()),
                    "paths in a diff are relative to this, so it has to be the checkout root");
            assertTrue(Files.isDirectory(scope.workTree().resolve("jzap-git")),
                    "and for this repository that root is the one holding the modules");
        }
    }

    @Test
    void classGranularityRecordsPathsRatherThanLines() throws Exception {
        try (GitScope scope = new GitScope(Path.of("").toAbsolutePath())) {
            Scope byClass = new Scope(ScopeKind.DIFF, "HEAD", Scope.LOCAL, "class", null,
                    List.of(), List.of(), List.of(), List.of(), List.of());

            // Whatever the working tree holds, class granularity must never record line numbers:
            // every mutant in a changed file is in scope.
            var changed = scope.resolve(byClass);
            changed.paths().forEach(path ->
                    assertTrue(changed.containsPath(path), "path scoping lost " + path));
        }
    }

    @Test
    void theEmptyTreePutsEveryTrackedLineInScope() throws Exception {
        try (GitScope scope = new GitScope(Path.of("").toAbsolutePath())) {
            var changed = scope.resolve(diff(Scope.EMPTY_TREE, "HEAD"));

            assertTrue(changed.lineCount() > 0,
                    "diffing against the empty tree is how a first commit is analysed, so it "
                            + "has to yield the whole codebase");
        }
    }
}
