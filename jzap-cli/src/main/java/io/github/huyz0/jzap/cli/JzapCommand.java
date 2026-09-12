package io.github.huyz0.jzap.cli;

import picocli.CommandLine.Command;

@Command(
        name = "jzap",
        mixinStandardHelpOptions = true,
        version = "jzap 0.1.0-SNAPSHOT",
        subcommands = {RunCommand.class, ListMutantsCommand.class, MutatorsCommand.class},
        synopsisSubcommandLabel = "COMMAND",
        description = "Fast, diff-aware mutation testing for the JVM.")
final class JzapCommand implements Runnable {

    @Override
    public void run() {
        // picocli prints usage when no subcommand is given; nothing else to do here.
        throw new picocli.CommandLine.ParameterException(
                new picocli.CommandLine(this), "a subcommand is required");
    }
}
