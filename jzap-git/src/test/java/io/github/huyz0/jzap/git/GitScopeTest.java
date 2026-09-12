package io.github.huyz0.jzap.git;

import io.github.huyz0.jzap.model.ChangedLines;
import io.github.huyz0.jzap.model.Scope;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitScopeTest {

    private static Git repo(Path dir) throws Exception {
        Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call();
        git.getRepository().getConfig().setString("user", null, "name", "Test");
        git.getRepository().getConfig().setString("user", null, "email", "test@example.com");
        git.getRepository().getConfig().save();
        return git;
    }

    private static void write(Path dir, String relative, String content) throws Exception {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void reportsOnlyTheLinesAModifiedCommitTouched(@TempDir Path dir) throws Exception {
        try (Git git = repo(dir)) {
            write(dir, "src/main/java/ex/A.java", "line1\nline2\nline3\nline4\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("base").call();

            write(dir, "src/main/java/ex/A.java", "line1\nCHANGED\nline3\nline4\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("change line 2").call();

            try (GitScope scope = new GitScope(dir)) {
                ChangedLines changed = scope.changedLines("HEAD~1", "HEAD", false);

                assertTrue(changed.containsLine("ex/A.java", 2), "line 2 changed");
                assertFalse(changed.containsLine("ex/A.java", 1), "line 1 did not change");
                assertFalse(changed.containsLine("ex/A.java", 3), "line 3 did not change");
            }
        }
    }

    @Test
    void localPicksUpStagedAndUnstagedChanges(@TempDir Path dir) throws Exception {
        try (Git git = repo(dir)) {
            write(dir, "ex/A.java", "a\nb\nc\n");
            write(dir, "ex/B.java", "a\nb\nc\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("base").call();

            write(dir, "ex/A.java", "a\nSTAGED\nc\n");
            git.add().addFilepattern("ex/A.java").call();
            write(dir, "ex/B.java", "a\nb\nUNSTAGED\n");

            try (GitScope scope = new GitScope(dir)) {
                ChangedLines changed = scope.resolve(Scope.diff("HEAD", Scope.LOCAL));

                assertTrue(changed.containsLine("ex/A.java", 2), "staged change should be in scope");
                assertTrue(changed.containsLine("ex/B.java", 3), "unstaged change should be in scope");
            }
        }
    }

    @Test
    void classGranularityIgnoresWhichLinesChanged(@TempDir Path dir) throws Exception {
        try (Git git = repo(dir)) {
            write(dir, "ex/A.java", "a\nb\nc\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("base").call();
            write(dir, "ex/A.java", "a\nCHANGED\nc\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("change").call();

            try (GitScope scope = new GitScope(dir)) {
                ChangedLines changed = scope.changedLines("HEAD~1", "HEAD", true);

                assertTrue(changed.containsPath("ex/A.java"));
                assertFalse(changed.containsLine("ex/A.java", 2),
                        "class granularity records the path, not individual lines");
            }
        }
    }

    @Test
    void deletedFilesAreOutOfScope(@TempDir Path dir) throws Exception {
        try (Git git = repo(dir)) {
            write(dir, "ex/Gone.java", "a\nb\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("base").call();
            Files.delete(dir.resolve("ex/Gone.java"));
            git.rm().addFilepattern("ex/Gone.java").call();
            git.commit().setMessage("delete").call();

            try (GitScope scope = new GitScope(dir)) {
                ChangedLines changed = scope.changedLines("HEAD~1", "HEAD", false);
                assertFalse(changed.containsPath("ex/Gone.java"),
                        "there is nothing left to mutate in a deleted file");
            }
        }
    }

    @Test
    void emptyTreeMeansEverythingIsNew(@TempDir Path dir) throws Exception {
        try (Git git = repo(dir)) {
            write(dir, "ex/A.java", "a\nb\nc\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("base").call();

            try (GitScope scope = new GitScope(dir)) {
                ChangedLines changed = scope.changedLines(Scope.EMPTY_TREE, "HEAD", false);
                assertTrue(changed.containsLine("ex/A.java", 1));
                assertTrue(changed.containsLine("ex/A.java", 3));
            }
        }
    }

    @Test
    void aFileWithNoChangesProducesNothing(@TempDir Path dir) throws Exception {
        try (Git git = repo(dir)) {
            write(dir, "ex/A.java", "a\nb\nc\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("base").call();

            try (GitScope scope = new GitScope(dir)) {
                assertTrue(scope.resolve(Scope.diff("HEAD", Scope.LOCAL)).isEmpty(),
                        "a clean tree has nothing in scope, and that is the fast path");
            }
        }
    }

    @Test
    void anUnknownRefSaysWhatTheValidOptionsAre(@TempDir Path dir) throws Exception {
        try (Git git = repo(dir)) {
            write(dir, "ex/A.java", "a\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("base").call();

            try (GitScope scope = new GitScope(dir)) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> scope.changedLines("no-such-ref", "HEAD", false));
                assertTrue(e.getMessage().contains("-Local-"), e.getMessage());
            }
        }
    }

    @Test
    void aDirectoryWithoutARepositoryIsRejectedWithAdvice(@TempDir Path dir) {
        assertThrows(RuntimeException.class, () -> new GitScope(dir.resolve("not-a-repo")));
    }
}
