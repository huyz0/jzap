package io.github.huyz0.jzap.core;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds the compiled classes to mutate.
 *
 * <p>Results are sorted by binary name so that probe ids, mutant ordering and therefore
 * report bytes are identical between runs. Determinism is not cosmetic here: the Gradle
 * build cache is only sound if the same inputs produce the same output.
 */
public final class ClassScanner {

    private final List<String> includeGlobs;
    private final List<String> excludeGlobs;

    public ClassScanner(List<String> includeGlobs, List<String> excludeGlobs) {
        this.includeGlobs = includeGlobs == null ? List.of() : List.copyOf(includeGlobs);
        this.excludeGlobs = excludeGlobs == null ? List.of() : List.copyOf(excludeGlobs);
    }

    public List<ClassBytes> scan(List<String> codePaths) {
        List<ClassBytes> found = new ArrayList<>();
        for (String path : codePaths) {
            Path p = Path.of(path);
            if (Files.isDirectory(p)) {
                scanDirectory(p, found);
            } else if (Files.isRegularFile(p) && (path.endsWith(".jar") || path.endsWith(".zip"))) {
                scanArchive(p, found);
            }
            // A code path that does not exist is not an error: a module may legitimately have
            // no compiled output yet. Callers report the resulting empty mutant set.
        }
        found.sort(Comparator.comparing(ClassBytes::binaryName));
        return found;
    }

    private void scanDirectory(Path root, List<ClassBytes> out) {
        try (var stream = Files.walk(root)) {
            for (Path f : stream.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".class"))
                    .toList()) {
                byte[] bytes = Files.readAllBytes(f);
                accept(bytes, root.relativize(f).toString(), out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot scan " + root, e);
        }
    }

    private void scanArchive(Path jar, List<ClassBytes> out) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    accept(in.readAllBytes(), jar + "!" + entry.getName(), out);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot scan " + jar, e);
        }
    }

    private void accept(byte[] bytes, String origin, List<ClassBytes> out) {
        String binaryName;
        try {
            binaryName = new ClassReader(bytes).getClassName().replace('/', '.');
        } catch (RuntimeException e) {
            // Not a class file we understand; skipping is safer than failing the whole run.
            return;
        }
        if (binaryName.endsWith("module-info") || binaryName.endsWith("package-info")) {
            return;
        }
        if (!matches(binaryName)) {
            return;
        }
        out.add(new ClassBytes(binaryName, bytes, origin));
    }

    private boolean matches(String binaryName) {
        for (String exclude : excludeGlobs) {
            if (Globs.matches(exclude, binaryName)) {
                return false;
            }
        }
        if (includeGlobs.isEmpty()) {
            return true;
        }
        for (String include : includeGlobs) {
            if (Globs.matches(include, binaryName)) {
                return true;
            }
        }
        return false;
    }
}
