package io.github.huyz0.jzap.cli;

import io.github.huyz0.jzap.core.Mutators;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "mutators", description = "List the available mutators.")
final class MutatorsCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("Default mutator set (matches PIT's DEFAULTS, with the same ids so that");
        System.out.println("mutant inventories can be compared directly):");
        Mutators.defaultIds().forEach(id -> System.out.println("  " + id));
        return RunCommand.EXIT_OK;
    }
}
