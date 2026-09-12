package io.github.huyz0.jzap.cli;

import picocli.CommandLine;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new JzapCommand()).execute(args));
    }
}
