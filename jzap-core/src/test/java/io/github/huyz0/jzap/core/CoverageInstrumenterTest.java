package io.github.huyz0.jzap.core;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import io.github.huyz0.jzap.agent.CoverageRecorder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageInstrumenterTest {

    private static final String SOURCE = """
            package ex;
            public class Probed {
                public int twice(int a) {
                    int b = a * 2;
                    return b;
                }
            }
            """;

    @Test
    void insertsOneProbeCallPerSourceLine() {
        byte[] original = InMemoryJavac.compile("ex.Probed", SOURCE).get("ex.Probed");
        ProbeIndex index = new ProbeIndex();

        byte[] instrumented = new CoverageInstrumenter(index).instrument("ex.Probed", original);

        List<String> calls = staticCallsTo(instrumented, CoverageInstrumenter.RECORDER, "hit");
        assertFalse(calls.isEmpty(), "no probe calls were inserted");
        assertEquals(index.size(), distinctProbeCount(instrumented),
                "each instrumented line should have exactly one probe id");
        StringWriter problems = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(instrumented), false, new PrintWriter(problems));
        assertTrue(problems.toString().isEmpty(), problems.toString());
    }

    @Test
    void probeIdsResolveBackToTheirLines() {
        byte[] original = InMemoryJavac.compile("ex.Probed", SOURCE).get("ex.Probed");
        ProbeIndex index = new ProbeIndex();
        new CoverageInstrumenter(index).instrument("ex.Probed", original);

        // The body spans the two statements of twice(), plus the implicit constructor line.
        assertTrue(index.size() >= 3, "expected a probe per line, got " + index.size());
        assertEquals(-1, index.lookup("ex.Probed", 9999));
    }

    private static List<String> staticCallsTo(byte[] classBytes, String owner, String name) {
        List<String> found = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String callOwner, String callName,
                                                String callDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && callOwner.equals(owner) && callName.equals(name)) {
                            found.add(methodName + " -> " + callOwner + "." + callName);
                        }
                    }
                };
            }
        }, 0);
        return found;
    }

    private static int distinctProbeCount(byte[] classBytes) {
        return staticCallsTo(classBytes, CoverageInstrumenter.RECORDER, "hit").size();
    }

    /**
     * Branching code is where instrumentation goes wrong: a probe emitted between a branch
     * target and its stack map frame yields a class that misbehaves and then fails
     * verification. Running the instrumented code is the only check that catches it.
     */
    private static final String BRANCHY_SOURCE = """
            package ex;
            public class Branchy {
                public int sumEven(int n) {
                    int total = 0;
                    for (int i = 0; i < n; i++) {
                        if (i % 2 == 0) {
                            total += i;
                        } else {
                            total -= 0;
                        }
                    }
                    return total;
                }
                public String classify(int n) {
                    switch (n) {
                        case 0: return "zero";
                        case 1: return "one";
                        default: return "many";
                    }
                }
                public int guarded(String s) {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        return -1;
                    } finally {
                        noop();
                    }
                }
                private void noop() {
                }
            }
            """;

    @Test
    void instrumentedBranchingCodeStillBehavesIdentically() throws Exception {
        Map<String, byte[]> compiled = InMemoryJavac.compile("ex.Branchy", BRANCHY_SOURCE);
        ProbeIndex index = new ProbeIndex();
        CoverageInstrumenter instrumenter = new CoverageInstrumenter(index);

        Map<String, byte[]> instrumented = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : compiled.entrySet()) {
            instrumented.put(e.getKey(), instrumenter.instrument(e.getKey(), e.getValue()));
        }

        CoverageRecorder.init(index.size());
        Class<?> c = InMemoryJavac.loader(instrumented).loadClass("ex.Branchy");
        Object o = c.getDeclaredConstructor().newInstance();

        assertEquals(6, c.getMethod("sumEven", int.class).invoke(o, 6));
        assertEquals("zero", c.getMethod("classify", int.class).invoke(o, 0));
        assertEquals("many", c.getMethod("classify", int.class).invoke(o, 7));
        assertEquals(42, c.getMethod("guarded", String.class).invoke(o, "42"));
        assertEquals(-1, c.getMethod("guarded", String.class).invoke(o, "nope"));

        assertTrue(CoverageRecorder.drain().length > 0, "instrumented code recorded no probes");
    }

    @Test
    void probesAreRecordedOnlyForLinesActuallyExecuted() throws Exception {
        Map<String, byte[]> compiled = InMemoryJavac.compile("ex.Branchy", BRANCHY_SOURCE);
        ProbeIndex index = new ProbeIndex();
        CoverageInstrumenter instrumenter = new CoverageInstrumenter(index);
        Map<String, byte[]> instrumented = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : compiled.entrySet()) {
            instrumented.put(e.getKey(), instrumenter.instrument(e.getKey(), e.getValue()));
        }

        CoverageRecorder.init(index.size());
        Class<?> c = InMemoryJavac.loader(instrumented).loadClass("ex.Branchy");
        Object o = c.getDeclaredConstructor().newInstance();
        CoverageRecorder.drain();

        c.getMethod("classify", int.class).invoke(o, 0);
        int[] hits = CoverageRecorder.drain();

        assertTrue(hits.length > 0, "classify recorded no probes");
        assertTrue(hits.length < index.size(),
                "calling one method should not report every line in the class as covered");
    }
}
