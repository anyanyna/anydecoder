package com.anydecoder.cli;

import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * Command line entry point.
 *
 * <p>Every command is usable by a person and by an agent, which is why each one can emit JSON.
 * The output is the contract: the Claude Code plugin drives repairs by reading a report, not by
 * parsing console prose, and a format that only reads well by eye would make that impossible.
 */
@CommandLine.Command(
        name = "anydecoder",
        mixinStandardHelpOptions = true,
        version = "anydecoder 0.1.1",
        description = "Recover Java source from class files, and prove the result behaves the same.",
        subcommands = {
                RunCommand.class,
                GateCommand.class,
                DiffTestCommand.class,
                CommandLine.HelpCommand.class
        })
public final class Main implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args));
    }
}
