package io.github.huyz0.jzap.cli;

import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.CacheConfig;
import io.github.huyz0.jzap.model.ChangedLines;
import io.github.huyz0.jzap.model.ModuleModel;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.report.ReportContext;
import io.github.huyz0.jzap.report.Reporter;
import io.github.huyz0.jzap.report.Reporters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "run", description = "Analyse mutants and write reports.")
final class RunCommand implements Callable<Integer> {

    static final int EXIT_OK = 0;
    static final int EXIT_THRESHOLD = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_FAILED = 3;

    @Mixin
    ModelOptions modelOptions;

    @Option(names = {"-r", "--reporters"}, split = ",", paramLabel = "ID",
            description = "Reporters to run. Default: whatever the model asks for.")
    List<String> reporters;

    @Option(names = {"-o", "--report-dir"}, paramLabel = "DIR",
            description = "Where to write reports. Default: build/reports/jzap")
    Path reportDir = Path.of("build", "reports", "jzap");

    @Option(names = "--threshold", paramLabel = "PERCENT",
            description = "Fail with exit code 1 if the mutation score falls below this.")
    Double threshold;

    @Option(names = "--fail-on-survivors",
            description = "Fail with exit code 1 if any mutant survives.")
    boolean failOnSurvivors;

    @Option(names = "--dry-run",
            description = "Resolve the model and scope, print what would be analysed, and stop.")
    boolean dryRun;

    @Option(names = {"-q", "--quiet"}, description = "Suppress progress output.")
    boolean quiet;

    @Option(names = "--cache-dir", paramLabel = "DIR",
            description = "Reuse verdicts from a previous run stored here, and update it. "
                    + "Off unless given: a cache whose whole question is whether reuse is sound "
                    + "should not start reusing without being asked.")
    Path cacheDir;

    @Option(names = "--daemon",
            description = "Run in a resident jzap for this project, starting one if needed. Saves "
                    + "the tool's own JVM startup, which is most of the wall clock on a cached run.")
    boolean daemon;

    @Option(names = "--engine", paramLabel = "schemata|naive",
            description = "schemata compiles every mutant of a class in at once and selects one "
                    + "with a field write; naive redefines the class per mutant and is kept as the "
                    + "reference implementation. Default: schemata.")
    String engine;

    @Option(names = {"-t", "--threads"}, paramLabel = "N",
            description = "Analysis threads. Default: whatever the model asks for, which "
                    + "defaults to one per available processor.")
    Integer threads;

    /** Set when this command is already running inside the daemon, to stop it recursing. */
    static final String IN_DAEMON = "jzap.inDaemon";

    @Override
    public Integer call() {
        if (daemon && System.getProperty(IN_DAEMON) == null) {
            return runInDaemon();
        }

        ProjectModel model;
        ChangedLines changed;
        try {
            model = modelOptions.read();
            changed = ScopeResolution.resolve(model, Path.of("").toAbsolutePath());
        } catch (IllegalArgumentException e) {
            System.err.println("jzap: " + e.getMessage());
            return EXIT_USAGE;
        }

        if (dryRun) {
            printResolution(model, changed);
            return EXIT_OK;
        }

        if (threads != null) {
            model = model.withThreads(threads);
        }
        if (cacheDir != null) {
            model = model.withCache(CacheConfig.at(cacheDir.toString()));
        }
        if (engine != null) {
            try {
                model = model.withEngine(engine);
            } catch (IllegalArgumentException e) {
                System.err.println("jzap: " + e.getMessage());
                return EXIT_USAGE;
            }
        }

        // Resolved before the analysis, not after it. A typo in --reporters used to surface as
        // an exception once every mutant had already been run, and picocli turned that into exit
        // code 1 -- the code that means "your mutation score is below the threshold". A usage
        // error has to be told apart from a verdict, and it has to fail before the work.
        List<String> reporterIds = reporters != null ? reporters : model.reporters();
        List<Reporter> resolvedReporters;
        try {
            resolvedReporters = new Reporters(System.out).resolve(reporterIds);
        } catch (IllegalArgumentException e) {
            System.err.println("jzap: " + e.getMessage());
            return EXIT_USAGE;
        }

        AnalysisResult result;
        try {
            result = new AnalysisEngine(model, new ConsoleListener(quiet)).analyse(changed);
        } catch (RuntimeException e) {
            System.err.println("jzap: analysis failed: " + e.getMessage());
            return EXIT_FAILED;
        }

        ReportContext context = ReportContext.of(reportDir, sourceRoots(model));
        resolvedReporters.forEach(reporter -> reporter.write(result, context));

        if (result.reusedFromCache() > 0) {
            System.out.println(result.reusedFromCache() + " of " + result.mutants().size()
                    + " verdicts reused from the cache at " + cacheDir);
        }
        if (!reporterIds.equals(List.of("console"))) {
            System.out.println("Reports written to " + reportDir.toAbsolutePath());
        }

        return exitCode(result);
    }

    /**
     * Hands this invocation to the resident jzap, starting one if there is none.
     *
     * <p>The daemon runs the very same command with {@code --daemon} dropped, so behaviour cannot
     * drift between the two paths: there is only one implementation.
     */
    private int runInDaemon() {
        List<String> forwarded = new ArrayList<>(List.of("run",
                "-m", modelOptions.modelFile.toAbsolutePath().toString(),
                "-o", reportDir.toAbsolutePath().toString()));
        if (modelOptions.from != null) {
            forwarded.addAll(List.of("--from", modelOptions.from));
        }
        if (modelOptions.to != null) {
            forwarded.addAll(List.of("--to", modelOptions.to));
        }
        if (cacheDir != null) {
            forwarded.addAll(List.of("--cache-dir", cacheDir.toString()));
        }
        if (threads != null) {
            forwarded.addAll(List.of("--threads", threads.toString()));
        }
        if (engine != null) {
            forwarded.addAll(List.of("--engine", engine));
        }
        if (threshold != null) {
            forwarded.addAll(List.of("--threshold", threshold.toString()));
        }
        if (quiet) {
            forwarded.add("--quiet");
        }

        var response = Daemon.run(modelOptions.modelFile, forwarded);
        if (response.isEmpty()) {
            if (!Daemon.start(modelOptions.modelFile, List.of("-D" + IN_DAEMON + "=true"))) {
                System.err.println("jzap: could not start a daemon; running in this process instead");
                daemon = false;
                return call();
            }
            response = Daemon.run(modelOptions.modelFile, forwarded);
        }
        if (response.isEmpty()) {
            System.err.println("jzap: the daemon did not answer; running in this process instead");
            daemon = false;
            return call();
        }
        System.out.print(response.get().output());
        return response.get().exitCode();
    }

    private int exitCode(AnalysisResult result) {
        if (threshold != null && result.mutationScore() < threshold) {
            System.err.printf("jzap: mutation score %.1f%% is below the threshold of %.1f%%%n",
                    result.mutationScore(), threshold);
            return EXIT_THRESHOLD;
        }
        if (failOnSurvivors && result.count(MutantStatus.SURVIVED) > 0) {
            System.err.println("jzap: " + result.count(MutantStatus.SURVIVED) + " mutant(s) survived");
            return EXIT_THRESHOLD;
        }
        return EXIT_OK;
    }

    private void printResolution(ProjectModel model, ChangedLines changed) {
        System.out.println("Scope kind: " + model.scope().kind()
                + " (" + model.scope().granularity() + " granularity)");
        if (changed == null) {
            System.out.println("Everything is in scope.");
        } else {
            System.out.println("In scope: " + changed.lineCount() + " lines across "
                    + changed.paths().size() + " files");
            changed.paths().stream().sorted().forEach(p -> System.out.println("  " + p));
            System.out.println();
            System.out.println("Note: analysis runs against the currently compiled code. The git");
            System.out.println("range only selects what to analyse; nothing is checked out.");
        }
        System.out.println();
        for (ModuleModel module : model.modules()) {
            System.out.println("Module " + module.id());
            module.mutableCodePaths().forEach(p -> System.out.println("  code:   " + p));
            module.testClassPaths().forEach(p -> System.out.println("  tests:  " + p));
            System.out.println("  classpath entries: " + module.testClasspath().size());
        }
    }

    private static List<Path> sourceRoots(ProjectModel model) {
        List<Path> roots = new ArrayList<>();
        for (ModuleModel module : model.modules()) {
            module.sourceRoots().forEach(r -> roots.add(Path.of(r)));
        }
        return roots;
    }

    /**
     * Single-line progress, because mutation analysis is long enough that silence looks broken.
     *
     * <p>Synchronised and volatile: the engine calls this from every analysis worker, and
     * interleaved half-lines are worse than no progress at all.
     */
    private static final class ConsoleListener implements AnalysisEngine.Listener {

        private final boolean quiet;
        private volatile String phase = "";

        ConsoleListener(boolean quiet) {
            this.quiet = quiet;
        }

        @Override
        public synchronized void phase(String name, String detail) {
            phase = name;
            if (!quiet) {
                System.err.println("jzap: " + name + (detail == null ? "" : " - " + detail));
            }
        }

        @Override
        public synchronized void progress(int done, int total) {
            if (!quiet && (done == total || done % 25 == 0)) {
                System.err.print("\rjzap: " + phase + " " + done + "/" + total);
                if (done == total) {
                    System.err.println();
                }
            }
        }

        @Override
        public synchronized void warning(String message) {
            System.err.println("jzap: warning: " + message);
        }
    }
}
