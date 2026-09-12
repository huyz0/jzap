import java.time.Duration

/**
 * A generated fixture big enough for timings to mean something.
 *
 * The sample-java fixture is deliberately tiny so its every verdict can be derived by hand,
 * which makes it useless for measuring throughput: a run there is almost entirely JVM startup.
 * Sources are generated rather than checked in so the size can be changed with one property.
 */
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val classCount = (findProperty("benchClasses") as String? ?: "40").toInt()
val generatedMain = layout.buildDirectory.dir("generated/main/java")
val generatedTest = layout.buildDirectory.dir("generated/test/java")

val generateSources = tasks.register("generateSources") {
    val mainDir = generatedMain
    val testDir = generatedTest
    val count = classCount
    inputs.property("classCount", count)
    outputs.dirs(mainDir, testDir)

    doLast {
        val main = mainDir.get().asFile.resolve("bench")
        val test = testDir.get().asFile.resolve("bench")
        main.mkdirs()
        test.mkdirs()
        main.listFiles()?.forEach { it.delete() }
        test.listFiles()?.forEach { it.delete() }

        for (i in 0 until count) {
            main.resolve("Unit$i.java").writeText(
                """
                package bench;

                /** Generated. Each method is shaped to attract a different mutator. */
                public class Unit$i {

                    public int scale(int value, int factor) {
                        if (factor > 10) {
                            factor = 10;
                        }
                        return value * factor + $i;
                    }

                    public int reduce(int[] values) {
                        int total = 0;
                        for (int i = 0; i < values.length; i++) {
                            total = total - values[i];
                        }
                        return total;
                    }

                    public boolean inRange(int value) {
                        return value >= 0 && value <= 100;
                    }

                    public String describe(int value) {
                        if (value < 0) {
                            return "negative";
                        }
                        return "non-negative";
                    }

                    public long accumulate(long seed) {
                        long acc = seed;
                        for (int i = 1; i < 4; i++) {
                            acc = acc + i * $i;
                        }
                        return acc;
                    }

                    public int untested(int value) {
                        return value / 2 + 1;
                    }
                }
                """.trimIndent() + "\n"
            )

            // Tests cover five of the six methods, so every run has genuine survivors and
            // genuinely uncovered mutants rather than a uniform all-killed result.
            test.resolve("Unit${i}Test.java").writeText(
                """
                package bench;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                class Unit${i}Test {

                    private final Unit$i unit = new Unit$i();

                    @Test
                    void scales() {
                        assertEquals(2 * 3 + $i, unit.scale(2, 3));
                        assertEquals(2 * 10 + $i, unit.scale(2, 50));
                    }

                    @Test
                    void reduces() {
                        assertEquals(-6, unit.reduce(new int[] {1, 2, 3}));
                    }

                    @Test
                    void checksRange() {
                        assertTrue(unit.inRange(50));
                        assertFalse(unit.inRange(101));
                    }

                    @Test
                    void describes() {
                        assertEquals("negative", unit.describe(-1));
                        assertEquals("non-negative", unit.describe(0));
                    }

                    @Test
                    void accumulates() {
                        assertEquals(7L + 1 * $i + 2 * $i + 3 * $i, unit.accumulate(7L));
                    }
                }
                """.trimIndent() + "\n"
            )
        }
        logger.lifecycle("generated $count bench classes and test classes")
    }
}

sourceSets {
    named("main") { java.srcDir(generatedMain) }
    named("test") { java.srcDir(generatedTest) }
}

tasks.named("compileJava") { dependsOn(generateSources) }
tasks.named("compileTestJava") { dependsOn(generateSources) }

// Not run as part of our build: this fixture exists to be analysed, not to be tested.
tasks.test { enabled = false }

val writeFixtureDescriptor = tasks.register("writeFixtureDescriptor") {
    val mainClasses = sourceSets["main"].output.classesDirs
    val testClasses = sourceSets["test"].output.classesDirs
    val testRuntime = sourceSets["test"].runtimeClasspath
    val sourceRoot = generatedMain
    val descriptor = layout.buildDirectory.file("fixture.properties")

    dependsOn(tasks.named("classes"), tasks.named("testClasses"))
    inputs.files(mainClasses, testClasses, testRuntime)
    outputs.file(descriptor)

    doLast {
        val separator = File.pathSeparator
        descriptor.get().asFile.writeText(buildString {
            appendLine("mainClasses=" + mainClasses.joinToString(separator) { it.absolutePath })
            appendLine("testClasses=" + testClasses.joinToString(separator) { it.absolutePath })
            appendLine("testRuntimeClasspath=" + testRuntime.files.joinToString(separator) { it.absolutePath })
            appendLine("sourceRoot=" + sourceRoot.get().asFile.absolutePath)
        })
    }
}
