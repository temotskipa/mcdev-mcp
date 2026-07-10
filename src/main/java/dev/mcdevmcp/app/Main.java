package dev.mcdevmcp.app;

import java.io.PrintWriter;
import picocli.CommandLine;

public final class Main {
    private Main() {}

    public static void main(String[] arguments) {
        System.exit(execute(
                arguments,
                Runtime.version().feature(),
                new PrintWriter(System.out, true),
                new PrintWriter(System.err, true)));
    }

    public static int execute(String[] arguments, int javaFeature, PrintWriter output, PrintWriter error) {
        if (javaFeature < 25) {
            error.printf("Java 25 or newer is required; detected Java %d.%n", javaFeature);
            error.flush();
            return 1;
        }

        var commandLine = new CommandLine(new McdevCommand());
        commandLine.setOut(output);
        commandLine.setErr(error);
        return commandLine.execute(arguments);
    }
}
