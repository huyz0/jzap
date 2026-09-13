package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.agent.CoverageRecorder;
import io.github.huyz0.jzap.core.testing.Fixture;
import io.github.huyz0.jzap.model.Scope;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads and runs the instrumented fixture classes in this JVM.
 *
 * <p>Worth having separately from the forked-JVM tests: when instrumentation is wrong, a
 * forked run reports it as a test failure several layers away from the cause, while this
 * test throws the actual VerifyError.
 */
class InstrumentedFixtureTest {

    private static Map<String, byte[]> instrumentedFixture(ProbeIndex index) {
        var module = new Fixture().model(Scope.all()).modules().get(0);
        List<ClassBytes> classes = new ClassScanner(List.of(), List.of()).scan(module.mutableCodePaths());
        assertFalse(classes.isEmpty(), "fixture classes were not found");
        CoverageInstrumenter instrumenter = new CoverageInstrumenter(index);
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (ClassBytes c : classes) {
            out.put(c.binaryName(), instrumenter.instrument(c.binaryName(), c.bytes()));
        }
        return out;
    }

    private static ClassLoader loaderFor(Map<String, byte[]> classes) {
        return new ClassLoader(InstrumentedFixtureTest.class.getClassLoader()) {
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

    @Test
    void instrumentedFixtureLoadsAndProducesTheSameAnswers() throws Exception {
        ProbeIndex index = new ProbeIndex();
        Map<String, byte[]> instrumented = instrumentedFixture(index);
        CoverageRecorder.init(index.size());

        Class<?> discount = loaderFor(instrumented).loadClass("sample.Discount");
        Object o = discount.getDeclaredConstructor().newInstance();

        assertEquals(90, discount.getMethod("applyPercent", int.class, int.class).invoke(o, 100, 10));
        assertEquals(50, discount.getMethod("applyPercent", int.class, int.class).invoke(o, 100, 90));
        assertEquals(true, discount.getMethod("isFree", int.class).invoke(o, 0));
        assertEquals(false, discount.getMethod("isFree", int.class).invoke(o, 5));
        assertTrue(CoverageRecorder.drain().length > 0, "no probes recorded");
    }
}
