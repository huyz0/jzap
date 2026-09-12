package io.github.huyz0.jzap.cli;

import io.github.huyz0.jzap.git.GitScope;
import io.github.huyz0.jzap.git.PatchScope;
import io.github.huyz0.jzap.model.ChangedLines;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;

import java.nio.file.Path;

/**
 * Turns a scope into concrete line ranges.
 *
 * <p>Lives in the CLI rather than the engine: the engine takes {@link ChangedLines} and has no
 * idea git exists, which is what lets a CI system feed it a patch file instead.
 */
final class ScopeResolution {

    private ScopeResolution() {
    }

    /** @return lines in scope, or null when everything is in scope */
    static ChangedLines resolve(ProjectModel model, Path workingDirectory) {
        Scope scope = model.scope();
        return switch (scope.kind()) {
            case ALL -> null;
            case PATCH -> {
                if (scope.patchFile() == null) {
                    throw new IllegalArgumentException("patch scoping needs --patch FILE");
                }
                yield PatchScope.fromFile(Path.of(scope.patchFile()));
            }
            case DIFF -> {
                try (GitScope git = new GitScope(workingDirectory)) {
                    yield git.resolve(scope);
                }
            }
        };
    }
}
