package io.github.huyz0.jzap.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding the classes to mutate.
 *
 * <p>The ordering assertions are the load-bearing ones. Probe ids, mutant ordinals and therefore
 * the bytes of every report are derived from this order, and the Gradle build cache is only sound
 * if the same inputs produce the same output. A scan that returned directory order would be
 * correct on one machine and wrong on the next.
 */
class ClassScannerTest {

    private static final List<String> NOTHING = List.of();

    /** Real class files, compiled by javac, with names that do not match their file order. */
    private static Path directoryWithClasses(Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("classes"));
        write(root, "zeta/Last.class", compiled("zeta.Last"));
        write(root, "alpha/First.class", compiled("alpha.First"));
        write(root, "alpha/Middle.class", compiled("alpha.Middle"));
        return root;
    }

    /** The bytes of a trivial class with this binary name. */
    private static byte[] compiled(String binaryName) {
        int lastDot = binaryName.lastIndexOf('.');
        String pkg = lastDot < 0 ? "" : "package " + binaryName.substring(0, lastDot) + ";";
        String simple = lastDot < 0 ? binaryName : binaryName.substring(lastDot + 1);
        return InMemoryJavac.compile(binaryName,
                pkg + " public class " + simple + " { public int value() { return 1; } }")
                .get(binaryName);
    }

    private static void write(Path root, String relative, byte[] bytes) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    @Test
    void findsEveryClassInADirectory(@TempDir Path dir) throws Exception {
        Path root = directoryWithClasses(dir);

        List<ClassBytes> found = new ClassScanner(NOTHING, NOTHING).scan(List.of(root.toString()));

        assertEquals(List.of("alpha.First", "alpha.Middle", "zeta.Last"),
                found.stream().map(ClassBytes::binaryName).toList());
    }

    @Test
    void resultsAreSortedByBinaryNameNotByFileOrder(@TempDir Path dir) throws Exception {
        Path root = directoryWithClasses(dir);

        List<String> first = new ClassScanner(NOTHING, NOTHING).scan(List.of(root.toString()))
                .stream().map(ClassBytes::binaryName).toList();
        List<String> again = new ClassScanner(NOTHING, NOTHING).scan(List.of(root.toString()))
                .stream().map(ClassBytes::binaryName).toList();

        assertEquals(first, again, "two scans of the same tree must agree");
        assertEquals(first.stream().sorted().toList(), first,
                "and the order has to be the name order, which is stable across machines");
    }

    @Test
    void findsClassesInsideAJar(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("lib.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("pkg/InJar.class"));
            out.write(compiled("pkg.InJar"));
            out.closeEntry();
            out.putNextEntry(new JarEntry("pkg/"));
            out.closeEntry();
            out.putNextEntry(new JarEntry("pkg/notes.txt"));
            out.write("ignored".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        List<ClassBytes> found = new ClassScanner(NOTHING, NOTHING).scan(List.of(jar.toString()));

        assertEquals(List.of("pkg.InJar"), found.stream().map(ClassBytes::binaryName).toList());
        assertTrue(found.get(0).origin().contains("!pkg/InJar.class"),
                "the origin should say which archive it came from: " + found.get(0).origin());
    }

    @Test
    void aCodePathThatDoesNotExistIsNotAnError(@TempDir Path dir) {
        List<ClassBytes> found = new ClassScanner(NOTHING, NOTHING)
                .scan(List.of(dir.resolve("never-compiled").toString()));

        assertEquals(List.of(), found,
                "a module may legitimately have no compiled output yet; the caller reports that");
    }

    @Test
    void aFileThatIsNotAnArchiveOrDirectoryIsSkipped(@TempDir Path dir) throws Exception {
        Path stray = Files.writeString(dir.resolve("notes.txt"), "hello");

        assertEquals(List.of(), new ClassScanner(NOTHING, NOTHING).scan(List.of(stray.toString())));
    }

    @Test
    void somethingThatIsNotAClassFileIsSkippedRatherThanFailingTheRun(@TempDir Path dir)
            throws Exception {
        Path root = Files.createDirectories(dir.resolve("classes"));
        write(root, "pkg/Good.class", compiled("pkg.Good"));
        write(root, "pkg/Corrupt.class", "not bytecode at all".getBytes(StandardCharsets.UTF_8));

        List<ClassBytes> found = new ClassScanner(NOTHING, NOTHING).scan(List.of(root.toString()));

        assertEquals(List.of("pkg.Good"), found.stream().map(ClassBytes::binaryName).toList(),
                "one unreadable file must not cost the whole analysis");
    }

    @Test
    void moduleInfoAndPackageInfoAreNotMutationTargets(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("classes"));
        write(root, "pkg/Real.class", compiled("pkg.Real"));
        // javac will not compile a class called package-info, so its bytes are made by renaming
        // one that is the same length. The filter looks only at the binary name.
        write(root, "pkg/package-info.class", renamed(compiled("pkg.package_info"),
                "package_info", "package-info"));

        List<ClassBytes> found = new ClassScanner(NOTHING, NOTHING).scan(List.of(root.toString()));

        assertEquals(List.of("pkg.Real"), found.stream().map(ClassBytes::binaryName).toList(),
                "a package declaration carries no behaviour, so it can carry no mutant");
    }

    // ------------------------------------------------------------ include and exclude

    @Test
    void anIncludeGlobNarrowsToMatchingClasses(@TempDir Path dir) throws Exception {
        Path root = directoryWithClasses(dir);

        List<ClassBytes> found = new ClassScanner(List.of("alpha.*"), NOTHING)
                .scan(List.of(root.toString()));

        assertEquals(List.of("alpha.First", "alpha.Middle"),
                found.stream().map(ClassBytes::binaryName).toList());
    }

    @Test
    void anExcludeGlobRemovesMatchingClasses(@TempDir Path dir) throws Exception {
        Path root = directoryWithClasses(dir);

        List<ClassBytes> found = new ClassScanner(NOTHING, List.of("alpha.*"))
                .scan(List.of(root.toString()));

        assertEquals(List.of("zeta.Last"), found.stream().map(ClassBytes::binaryName).toList());
    }

    @Test
    void excludeWinsOverInclude(@TempDir Path dir) throws Exception {
        Path root = directoryWithClasses(dir);

        List<ClassBytes> found = new ClassScanner(List.of("alpha.*"), List.of("alpha.Middle"))
                .scan(List.of(root.toString()));

        assertEquals(List.of("alpha.First"), found.stream().map(ClassBytes::binaryName).toList(),
                "an exclusion is a statement about what must never be mutated, so it is final");
    }

    @Test
    void severalIncludesAreAlternatives(@TempDir Path dir) throws Exception {
        Path root = directoryWithClasses(dir);

        List<ClassBytes> found = new ClassScanner(List.of("zeta.*", "alpha.First"), NOTHING)
                .scan(List.of(root.toString()));

        assertEquals(List.of("alpha.First", "zeta.Last"),
                found.stream().map(ClassBytes::binaryName).toList());
    }

    @Test
    void nullGlobListsAreTreatedAsNoFilter(@TempDir Path dir) throws Exception {
        Path root = directoryWithClasses(dir);

        List<ClassBytes> found = new ClassScanner(null, null).scan(List.of(root.toString()));

        assertEquals(3, found.size());
    }

    @Test
    void scanningSeveralCodePathsMergesThem(@TempDir Path dir) throws Exception {
        Path one = Files.createDirectories(dir.resolve("one"));
        Path two = Files.createDirectories(dir.resolve("two"));
        write(one, "a/One.class", compiled("a.One"));
        write(two, "b/Two.class", compiled("b.Two"));

        List<ClassBytes> found = new ClassScanner(NOTHING, NOTHING)
                .scan(List.of(one.toString(), two.toString()));

        assertEquals(List.of("a.One", "b.Two"), found.stream().map(ClassBytes::binaryName).toList());
    }

    /** A byte-for-byte rename, which needs the two names to be the same length. */
    private static byte[] renamed(byte[] classBytes, String from, String to) {
        assertEquals(from.length(), to.length(), "the rename relies on equal lengths");
        byte[] a = from.getBytes(StandardCharsets.UTF_8);
        byte[] b = to.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + a.length <= classBytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < a.length; j++) {
                if (classBytes[i + j] != a[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(b, 0, classBytes, i, b.length);
            }
        }
        return classBytes;
    }

    @Test
    void aCorruptArchiveSaysWhichOne(@TempDir Path dir) throws Exception {
        Path jar = Files.writeString(dir.resolve("broken.jar"), "this is not a zip");

        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> new ClassScanner(NOTHING, NOTHING).scan(List.of(jar.toString())));
        assertTrue(e.getMessage().contains("broken.jar"), e.getMessage());
    }
}
