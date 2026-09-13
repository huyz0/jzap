package io.github.huyz0.jzap.git;

import io.github.huyz0.jzap.model.ChangedLines;
import io.github.huyz0.jzap.model.Scope;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves a git range into the source lines that are in scope.
 *
 * <p>The engine never sees this class. It consumes {@link ChangedLines}, which a CI system can
 * also produce from a patch file, so diff-scoped analysis does not require a git checkout to
 * be present. See docs/architecture.md.
 *
 * <p>One semantic worth stating plainly, because both Mull and arcmutate document it as a
 * source of misleading results: the range only <em>selects</em> what to analyse. Analysis
 * always runs against the currently compiled code. Nothing is checked out, so pointing
 * {@code from}/{@code to} at an old commit whose files have since changed will produce
 * results that do not describe that commit.
 */
public final class GitScope implements AutoCloseable {

    private final Repository repository;

    public GitScope(Path anyPathInsideRepository) {
        // Checked before build() rather than after. build() throws its own
        // "One of setGitDir or setWorkTree must be called" when findGitDir found nothing, which
        // tells a user running outside a checkout -- a source tarball, a container that did not
        // copy .git -- nothing at all about what jzap wanted or what to do instead.
        FileRepositoryBuilder builder = new FileRepositoryBuilder()
                .findGitDir(anyPathInsideRepository.toFile())
                .readEnvironment();
        if (builder.getGitDir() == null) {
            throw new IllegalArgumentException("no git repository found at or above "
                    + anyPathInsideRepository.toAbsolutePath()
                    + ". Use a patch file instead if this tree is not a git checkout.");
        }
        try {
            this.repository = builder.build();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open the git repository containing "
                    + anyPathInsideRepository, e);
        }
    }

    /** Repository root, which diff paths are relative to. */
    public Path workTree() {
        return repository.getWorkTree().toPath();
    }

    public ChangedLines resolve(Scope scope) {
        String from = scope.from() == null || scope.from().isBlank() ? "HEAD" : scope.from();
        String to = scope.to() == null || scope.to().isBlank() ? Scope.LOCAL : scope.to();
        return changedLines(from, to, scope.isClassGranularity());
    }

    /**
     * @param classGranularity record only which files changed, not which lines, so that every
     *                         mutant in a touched class is analysed
     */
    public ChangedLines changedLines(String from, String to, boolean classGranularity) {
        ChangedLines changed = ChangedLines.empty();
        try (ObjectReader reader = repository.newObjectReader();
             Git git = new Git(repository);
             DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

            formatter.setRepository(repository);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            // Renames are not followed: a renamed file's every line looks changed, which would
            // flood a pull request with mutants for code nobody touched. arcmutate ignores
            // renames for the same reason.
            formatter.setDetectRenames(false);

            List<DiffEntry> entries = formatter.scan(treeIterator(reader, from), treeIterator(reader, to));
            for (DiffEntry entry : entries) {
                if (entry.getChangeType() == DiffEntry.ChangeType.DELETE) {
                    continue;   // nothing left to mutate
                }
                String newPath = entry.getNewPath();
                if (newPath == null || newPath.equals(DiffEntry.DEV_NULL)) {
                    continue;
                }
                if (classGranularity) {
                    changed.addPath(newPath);
                    continue;
                }
                for (Edit edit : formatter.toFileHeader(entry).toEditList()) {
                    if (edit.getEndB() > edit.getBeginB()) {
                        // Edit offsets are 0-based and end-exclusive; source lines are 1-based.
                        changed.addRange(newPath, edit.getBeginB() + 1, edit.getEndB());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot diff " + from + ".." + to, e);
        }
        return changed;
    }

    private AbstractTreeIterator treeIterator(ObjectReader reader, String ref) throws IOException {
        if (Scope.LOCAL.equals(ref)) {
            // Staged and unstaged changes together, which is what a developer means by
            // "what I have right now".
            return new FileTreeIterator(repository);
        }
        if (Scope.EMPTY_TREE.equals(ref)) {
            return new EmptyTreeIterator();
        }
        ObjectId resolved = repository.resolve(ref + "^{tree}");
        if (resolved == null) {
            ObjectId commit = repository.resolve(ref);
            if (commit == null) {
                throw new IllegalArgumentException("cannot resolve git ref '" + ref + "'. Use a ref, "
                        + Scope.LOCAL + " for uncommitted changes, or " + Scope.EMPTY_TREE
                        + " for the empty tree.");
            }
            try (RevWalk walk = new RevWalk(repository)) {
                resolved = walk.parseCommit(commit).getTree().getId();
            }
        }
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, resolved);
        return parser;
    }

    @Override
    public void close() {
        repository.close();
    }
}
