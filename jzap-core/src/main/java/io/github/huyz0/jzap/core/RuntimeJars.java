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
final class RuntimeJars {

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

    static Jars discover() {
        return discoverWith(RuntimeJars.class.getClassLoader());
    }

    /**
     * Discovery against a given classloader.
     *
     * <p>The loader is a parameter so the diagnostic for a missing artefact can be tested. It is
     * a message about someone's packaging, and the only way to produce the condition on purpose
     * is to ask a loader that has nothing.
     */
    static Jars discoverWith(ClassLoader loader) {
        Path agent = locate(loader, AGENT_MARKER, "jzap-agent");
        Path minion = locate(loader, MINION_MARKER, "jzap-minion");
        Path wire = locate(loader, WIRE_MARKER, "jzap-wire");
        return new Jars(asAgentJar(agent), minion, wire);
    }

    private static Path locate(ClassLoader loader, String marker, String module) {
        URL url = loader.getResource(marker);
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
    static Path asAgentJar(Path agentPath) {
        if (Files.isRegularFile(agentPath)) {
            return agentPath;
        }
        return packageAsAgentJar(agentPath);
    }

    /**
     * Packages a directory of classes into a jar carrying the agent manifest.
     *
     * <p>Needed because {@code -javaagent} will only accept a jar, and a jar will only be treated
     * as an agent if its manifest says so. Running from class directories is not an edge case:
     * it is how every test and every IDE run works, so this path has to be as reliable as the
     * packaged one.
     */
    static Path packageAsAgentJar(Path agentPath) {
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
