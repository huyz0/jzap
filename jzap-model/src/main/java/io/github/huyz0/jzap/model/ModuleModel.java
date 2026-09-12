package io.github.huyz0.jzap.model;

import java.util.List;

/**
 * One unit of compiled code plus the tests that exercise it.
 *
 * <p>Everything here is computed by a build-tool adapter. The engine reads it and obeys;
 * it never inspects a build file. See docs/architecture.md.
 *
 * @param id                 stable module identifier, e.g. a Gradle project path
 * @param mutableCodePaths   class directories or jars holding the code to mutate
 * @param sourceRoots        source directories, used to map lines to files and to render reports
 * @param testClassPaths     class directories holding compiled tests, scanned for test discovery
 * @param testClasspath      full classpath needed to run the tests, including test classes
 * @param javaHome           JDK to run analysis under; null means the JDK running jzap
 * @param jvmArgs            extra arguments for the forked analysis JVM
 * @param kotlinVersion      Kotlin version that produced the bytecode, when applicable
 */
public record ModuleModel(
        String id,
        List<String> mutableCodePaths,
        List<String> sourceRoots,
        List<String> testClassPaths,
        List<String> testClasspath,
        String javaHome,
        List<String> jvmArgs,
        String kotlinVersion) {

    public ModuleModel {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("module id is required");
        }
        mutableCodePaths = copy(mutableCodePaths);
        sourceRoots = copy(sourceRoots);
        testClassPaths = copy(testClassPaths);
        testClasspath = copy(testClasspath);
        jvmArgs = copy(jvmArgs);
    }

    private static List<String> copy(List<String> in) {
        return in == null ? List.of() : List.copyOf(in);
    }
}
