package io.github.huyz0.jzap.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Locates the jars the analysis JVM has to be started with.
 *
 * <p>Discovery is by resource lookup rather than configuration, so the CLI, the build-tool
 * adapters and the tests all find the same artefacts without anyone having to pass paths
 * around. When running from class directories — which is how tests and IDE runs work — the
 * agent directory is packaged into a temporary jar, because {@code -javaagent} requires a
 * jar with a manifest.
 */
public final class RuntimeJars {

    /**
     * @param agent  jar to pass to {@code -javaagent}
     * @param minion classpath entry holding the minion
     * @param wire   classpath entry holding the protocol
     */
    public record Jars(Path agent, Path minion, Path wire) {
    }

    private static final String AGENT_MARKER = "io/github/huyz0/jzap/agent/JzapAgent.class";
    private static final String MINION_MARKER = "io/github/huyz0/jzap/minion/Minion.class";
    private static final String WIRE_MARKER = "io/github/huyz0/jzap/wire/Wire.class";

    private RuntimeJars() {
    }

    public static Jars discover() {
        Path agent = locate(AGENT_MARKER, "jzap-agent");
        Path minion = locate(MINION_MARKER, "jzap-minion");
        Path wire = locate(WIRE_MARKER, "jzap-wire");
        return new Jars(asAgentJar(agent), minion, wire);
    }

    private static Path locate(String marker, String module) {
        URL url = RuntimeJars.class.getClassLoader().getResource(marker);
        if (url == null) {
            throw new IllegalStateException(module + " is not on the jzap classpath, so no "
                    + "analysis JVM can be started. Add " + module + " as a dependency of "
                    + "whatever is invoking jzap.");
        }
        try {
            String form = url.toString();
            if (form.startsWith("jar:")) {
                String jar = form.substring("jar:".length(), form.indexOf("!/"));
                return Path.of(java.net.URI.create(jar));
            }
            Path classFile = Path.of(url.toURI());
            // Walk back up out of the package directories to the classpath root.
            Path root = classFile;
            for (int i = 0; i < marker.split("/").length; i++) {
                root = root.getParent();
            }
            return root;
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot interpret location of " + module + ": " + url, e);
        }
    }

    /** Wraps a class directory in a jar with the agent manifest, if it is not already one. */
    private static Path asAgentJar(Path agentPath) {
        if (Files.isRegularFile(agentPath)) {
            return agentPath;
        }
        try {
            Path jar = Files.createTempFile("jzap-agent-", ".jar");
            Manifest manifest = new Manifest();
            Attributes main = manifest.getMainAttributes();
            main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            main.putValue("Premain-Class", "io.github.huyz0.jzap.agent.JzapAgent");
            main.putValue("Agent-Class", "io.github.huyz0.jzap.agent.JzapAgent");
            main.putValue("Can-Retransform-Classes", "true");
            main.putValue("Can-Redefine-Classes", "true");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
                 Stream<Path> files = Files.walk(agentPath)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    out.putNextEntry(new JarEntry(agentPath.relativize(f).toString().replace('\\', '/')));
                    out.write(Files.readAllBytes(f));
                    out.closeEntry();
                }
            }
            jar.toFile().deleteOnExit();
            return jar;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot package the jzap agent from " + agentPath, e);
        }
    }
}
