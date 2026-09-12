package io.github.huyz0.jzap.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent shares a classloader with the code under test, so every package it references
 * becomes a package every analysed project must tolerate. PIT relocates its minion
 * dependencies for this reason; jzap avoids having any to relocate, and this test is what
 * keeps that true.
 */
class AgentHasNoThirdPartyDependenciesTest {

    private static final Set<String> ALLOWED_PREFIXES = Set.of("io/github/huyz0/jzap/agent/", "java/", "javax/", "jdk/");

    @Test
    void agentClassesReferenceOnlyJdkAndItself() throws Exception {
        Path classes = Path.of("build/classes/java/main");
        assertTrue(Files.isDirectory(classes), "agent classes not built at " + classes.toAbsolutePath());

        Set<String> offenders = new TreeSet<>();
        try (Stream<Path> s = Files.walk(classes)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".class")).toList()) {
                for (String ref : referencedTypes(Files.readAllBytes(p))) {
                    boolean allowed = ALLOWED_PREFIXES.stream().anyMatch(ref::startsWith);
                    if (!allowed) {
                        offenders.add(ref + " (from " + classes.relativize(p) + ")");
                    }
                }
            }
        }

        assertEquals(Set.of(), offenders,
                "agent must not reference third-party packages; found: " + offenders);
    }

    /**
     * Extracts class references from the constant pool without using a bytecode library,
     * because pulling ASM into this module's test would defeat the point of the check.
     */
    private static List<String> referencedTypes(byte[] cf) {
        List<String> out = new ArrayList<>();
        int p = 10; // magic(4) + minor(2) + major(2) + constant_pool_count(2)
        int count = ((cf[8] & 0xff) << 8) | (cf[9] & 0xff);
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = cf[p++] & 0xff;
            switch (tag) {
                case 1 -> { // Utf8
                    int len = ((cf[p] & 0xff) << 8) | (cf[p + 1] & 0xff);
                    utf8[i] = new String(cf, p + 2, len, java.nio.charset.StandardCharsets.UTF_8);
                    p += 2 + len;
                }
                case 7 -> { // Class
                    classNameIndex[i] = ((cf[p] & 0xff) << 8) | (cf[p + 1] & 0xff);
                    p += 2;
                }
                case 8, 16, 19, 20 -> p += 2;
                case 15 -> p += 3;
                case 3, 4, 9, 10, 11, 12, 17, 18 -> p += 4;
                case 5, 6 -> { // Long, Double take two slots
                    p += 8;
                    i++;
                }
                default -> throw new IllegalStateException("unexpected constant pool tag " + tag);
            }
        }
        for (int i = 1; i < count; i++) {
            if (classNameIndex[i] != 0) {
                String name = utf8[classNameIndex[i]];
                if (name != null) {
                    String normalised = name.startsWith("[") ? stripArray(name) : name;
                    if (normalised != null) {
                        out.add(normalised + "/");
                    }
                }
            }
        }
        return out;
    }

    private static String stripArray(String desc) {
        int i = 0;
        while (i < desc.length() && desc.charAt(i) == '[') {
            i++;
        }
        if (i < desc.length() && desc.charAt(i) == 'L') {
            return desc.substring(i + 1, desc.length() - 1);
        }
        return null;
    }
}
