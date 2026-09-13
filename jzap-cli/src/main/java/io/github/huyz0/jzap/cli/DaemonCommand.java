package io.github.huyz0.jzap.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "daemon", description = "Start, stop or query the resident jzap for a project.")
final class DaemonCommand implements Callable<Integer> {

    @Option(names = {"-m", "--project-model"}, required = true, paramLabel = "FILE",
            description = "The project model this daemon serves. One daemon per model.")
    Path modelFile;

    @Option(names = "--serve", hidden = true,
            description = "Run as the daemon itself rather than talking to one.")
    boolean serve;

    @Option(names = "--stop", description = "Stop the daemon for this model.")
    boolean stop;

    @Option(names = "--status", description = "Report whether a daemon is running for this model.")
    boolean status;

    @Override
    public Integer call() throws Exception {
        if (serve) {
            Daemon.serve(modelFile, System.out);
            return RunCommand.EXIT_OK;
        }
        if (stop) {
            return Daemon.stop(modelFile)
                    .map(response -> {
                        System.out.println("jzap: daemon stopped");
                        return RunCommand.EXIT_OK;
                    })
                    .orElseGet(() -> {
                        System.out.println("jzap: no daemon was running for " + modelFile);
                        return RunCommand.EXIT_OK;
                    });
        }
        if (status) {
            boolean alive = Daemon.ping(modelFile).isPresent();
            System.out.println(alive
                    ? "jzap: daemon running for " + modelFile.toAbsolutePath()
                    : "jzap: no daemon running for " + modelFile.toAbsolutePath());
            if (alive) {
                System.out.println("  port file: " + Daemon.portFile(modelFile));
            }
            return RunCommand.EXIT_OK;
        }
        if (!Files.isRegularFile(modelFile)) {
            System.err.println("jzap: no project model at " + modelFile.toAbsolutePath());
            return RunCommand.EXIT_USAGE;
        }
        boolean started = Daemon.start(modelFile, List.of());
        System.out.println(started
                ? "jzap: daemon started for " + modelFile.toAbsolutePath()
                : "jzap: the daemon did not come up within 30s");
        return started ? RunCommand.EXIT_OK : RunCommand.EXIT_FAILED;
    }
}
