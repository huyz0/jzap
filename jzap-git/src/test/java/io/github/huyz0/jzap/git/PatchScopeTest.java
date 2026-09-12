package io.github.huyz0.jzap.git;

import io.github.huyz0.jzap.model.ChangedLines;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchScopeTest {

    @Test
    void mapsAddedLinesToTheirNewFilePositions() {
        ChangedLines changed = PatchScope.parse(List.of(
                "diff --git a/ex/A.java b/ex/A.java",
                "index 1111111..2222222 100644",
                "--- a/ex/A.java",
                "+++ b/ex/A.java",
                "@@ -1,4 +1,5 @@",
                " line1",
                "-old2",
                "+new2",
                "+extra",
                " line3",
                " line4"));

        assertTrue(changed.containsLine("ex/A.java", 2), "new2 lands on line 2");
        assertTrue(changed.containsLine("ex/A.java", 3), "extra lands on line 3");
        assertFalse(changed.containsLine("ex/A.java", 1), "context line 1 is unchanged");
        assertFalse(changed.containsLine("ex/A.java", 4), "context line 4 is unchanged");
    }

    @Test
    void handlesSeveralFilesAndHunks() {
        ChangedLines changed = PatchScope.parse(List.of(
                "--- a/ex/A.java",
                "+++ b/ex/A.java",
                "@@ -10,3 +10,4 @@",
                " keep",
                "+added11",
                " keep",
                " keep",
                "--- a/ex/B.java",
                "+++ b/ex/B.java",
                "@@ -1,2 +1,2 @@",
                "-gone",
                "+fresh",
                " keep"));

        assertTrue(changed.containsLine("ex/A.java", 11));
        assertTrue(changed.containsLine("ex/B.java", 1));
        assertFalse(changed.containsLine("ex/B.java", 2));
    }

    @Test
    void aDeletionOnlyHunkContributesNothing() {
        ChangedLines changed = PatchScope.parse(List.of(
                "--- a/ex/A.java",
                "+++ b/ex/A.java",
                "@@ -1,3 +1,2 @@",
                " keep",
                "-removed",
                " keep"));

        assertTrue(changed.isEmpty(), "removed code cannot hold a mutant");
    }

    @Test
    void singleLineHunkHeaderWithoutACountIsAccepted() {
        ChangedLines changed = PatchScope.parse(List.of(
                "--- a/ex/A.java",
                "+++ b/ex/A.java",
                "@@ -5 +5 @@",
                "-old",
                "+new"));

        assertTrue(changed.containsLine("ex/A.java", 5));
    }
}
