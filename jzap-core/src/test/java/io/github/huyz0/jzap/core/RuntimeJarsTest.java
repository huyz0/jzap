package io.github.huyz0.jzap.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locating the jars an analysis JVM has to be started with.
 *
 * <p>The interesting case is running from class directories rather than jars, which is how every
 * test and every IDE run works. {@code -javaagent} will only accept a jar, and will only treat it
 * as an agent if the manifest says so, so the directory has to be packaged on the fly. If that
 * packaging were wrong the failure would arrive as a JVM that refuses to start, from a command
 * line nobody sees.
 */
class RuntimeJarsTest {

    /**
     * A missing artefact names the module and what to do about it.
     *
     * <p>This module's own tests do not have the minion on their classpath, which is exactly the
     * situation a user hits when jzap is embedded without its runtime pieces. The successful
     * discovery is covered where it is real: every end-to-end test starts an analysis JVM.
     */
    @Test
    void aMissingRuntimeArtefactSaysWhichOneAndWhatToAddIt() {
        IllegalStateException e = assertThrows(IllegalStateException.class, RuntimeJars::discover);

        assertTrue(e.getMessage().contains("jzap-minion"),
                "the message has to name the module: " + e.getMessage());
        assertTrue(e.getMessage().contains("as a dependency"),
                "and say what to do, since the cause is always a packaging mistake: "
                        + e.getMessage());
    }

    @Test
    void anAgentThatIsAlreadyAJarIsUsedAsItIs(@TempDir Path dir) throws Exception {
        Path existing = Files.createFile(dir.resolve("agent.jar"));

        assertSame(existing, RuntimeJars.asAgentJar(existing),
                "repackaging one would be pointless work on every run");
    }

    @Test
    void aClassDirectoryIsPackagedWithTheAgentManifest(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes/io/github/huyz0/jzap/agent"));
        Files.write(classes.resolve("JzapAgent.class"), new byte[]{1, 2, 3});
        Files.write(classes.resolve("MutantSwitch.class"), new byte[]{4, 5});

        Path jar = RuntimeJars.packageAsAgentJar(dir.resolve("classes"));

        assertTrue(Files.isRegularFile(jar));
        try (JarFile packaged = new JarFile(jar.toFile())) {
            Attributes manifest = packaged.getManifest().getMainAttributes();
            assertEquals("io.github.huyz0.jzap.agent.JzapAgent", manifest.getValue("Premain-Class"),
                    "without this the JVM will not load it as an agent");
            assertEquals("io.github.huyz0.jzap.agent.JzapAgent", manifest.getValue("Agent-Class"));
            assertEquals("true", manifest.getValue("Can-Retransform-Classes"),
                    "installing a mutant into a loaded class depends on retransformation");
            assertEquals("true", manifest.getValue("Can-Redefine-Classes"));

            assertNotNull(packaged.getEntry("io/github/huyz0/jzap/agent/JzapAgent.class"),
                    "entries have to keep their package paths or the classes will not load");
            assertNotNull(packaged.getEntry("io/github/huyz0/jzap/agent/MutantSwitch.class"));
        }
    }

    @Test
    void packagedEntriesUseForwardSlashesWhateverTheHostSeparatorIs(@TempDir Path dir)
            throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes/a/b"));
        Files.write(classes.resolve("C.class"), new byte[]{1});

        Path jar = RuntimeJars.packageAsAgentJar(dir.resolve("classes"));

        try (JarFile packaged = new JarFile(jar.toFile())) {
            assertNotNull(packaged.getEntry("a/b/C.class"),
                    "a jar entry name is always slash-separated, even on Windows");
        }
    }

    @Test
    void theContentOfEachClassSurvivesPackaging(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes/pkg"));
        byte[] content = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 1, 2, 3};
        Files.write(classes.resolve("Thing.class"), content);

        Path jar = RuntimeJars.packageAsAgentJar(dir.resolve("classes"));

        try (JarFile packaged = new JarFile(jar.toFile())) {
            try (var in = packaged.getInputStream(packaged.getEntry("pkg/Thing.class"))) {
                org.junit.jupiter.api.Assertions.assertArrayEquals(content, in.readAllBytes());
            }
        }
    }

    @Test
    void anEmptyDirectoryStillProducesALoadableAgentJar(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("empty"));

        Path jar = RuntimeJars.packageAsAgentJar(classes);

        try (JarFile packaged = new JarFile(jar.toFile())) {
            assertNotNull(packaged.getManifest(),
                    "the manifest is what makes it an agent, so it must be there regardless");
        }
    }
}
