package io.github.huyz0.jzap.core;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles Java source to class bytes in memory, so fixtures can be written inline in the
 * test that asserts on them. Keeping the source next to its expected mutants is what makes
 * these hand-written expectations reviewable.
 */
final class InMemoryJavac {

    private InMemoryJavac() {
    }

    static Map<String, byte[]> compile(String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system Java compiler; tests must run on a JDK");
        }
        Map<String, byte[]> output = new LinkedHashMap<>();
        StandardJavaFileManager standard = compiler.getStandardFileManager(null, null, null);
        var fileManager = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String name,
                                                       JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + name.replace('.', '/') + ".class"),
                        JavaFileObject.Kind.CLASS) {
                    @Override
                    public OutputStream openOutputStream() {
                        return new ByteArrayOutputStream() {
                            @Override
                            public void close() throws IOException {
                                super.close();
                                output.put(name, toByteArray());
                            }
                        };
                    }
                };
            }
        };
        JavaFileObject sourceFile = new SimpleJavaFileObject(
                URI.create("mem:///" + className.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var diagnostics = new javax.tools.DiagnosticCollector<JavaFileObject>();
        boolean ok = compiler.getTask(null, fileManager, diagnostics,
                List.of("-g", "-parameters"), null, List.of(sourceFile)).call();
        if (!ok) {
            throw new IllegalStateException("fixture failed to compile: " + diagnostics.getDiagnostics());
        }
        return output;
    }

    /** Loads classes from compiled bytes, applying overrides by binary name. */
    static ClassLoader loader(Map<String, byte[]> classes) {
        return new ClassLoader(InMemoryJavac.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }
}
