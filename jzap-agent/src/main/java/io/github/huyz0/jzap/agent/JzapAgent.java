package io.github.huyz0.jzap.agent;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point. Its only job is to capture {@link Instrumentation} and install
 * the override transformer.
 *
 * <p>This class and its package must stay free of third-party dependencies. It shares a
 * classloader with the code under test, so a dependency here becomes a dependency of every
 * project jzap analyses.
 */
public final class JzapAgent {

    private static volatile Instrumentation instrumentation;

    private JzapAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        install(inst);
    }

    private static synchronized void install(Instrumentation inst) {
        if (instrumentation != null) {
            return;
        }
        instrumentation = inst;
        inst.addTransformer(new OverrideTransformer(), true);
    }

    public static Instrumentation instrumentation() {
        Instrumentation inst = instrumentation;
        if (inst == null) {
            throw new IllegalStateException(
                    "jzap agent is not loaded. The analysis JVM must be started with "
                            + "-javaagent pointing at the jzap agent jar.");
        }
        return inst;
    }

    public static boolean isLoaded() {
        return instrumentation != null;
    }
}
