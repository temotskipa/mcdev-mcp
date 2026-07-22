package dev.mcdevmcp.app;

import picocli.CommandLine;

import java.io.PrintWriter;
import java.util.Objects;

@SuppressWarnings("ExtractMethodRecommender")
public final class Main {
    public static int execute(String[] arguments, int javaFeature, PrintWriter output, PrintWriter error) {
        if (javaFeature < 25) {
            return rejectOldJava(javaFeature, error);
        }
        return execute(arguments, javaFeature, output, error, CommandContext.production());
    }

    // Allows command tests to use deterministic paths and collaborators.
    public static int execute(String[] arguments, int javaFeature, PrintWriter output, PrintWriter error, CommandContext context) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(context, "context");
        if (javaFeature < 25) {
            return rejectOldJava(javaFeature, error);
        }

        var commandLine = new CommandLine(new McdevCommand(), context);
        commandLine.setOut(output);
        commandLine.setErr(error);
        commandLine.setParameterExceptionHandler((exception, _) -> report(exception.getCommandLine(), exception.getMessage(), exception.getCommandLine().getCommandSpec().exitCodeOnInvalidInput()));
        commandLine.setExecutionExceptionHandler((exception, command, _) -> report(command, conciseMessage(exception), command.getCommandSpec().exitCodeOnExecutionException()));
        return commandLine.execute(arguments);
    }

    private static String conciseMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static int rejectOldJava(int javaFeature, PrintWriter error) {
        Objects.requireNonNull(error, "error");
        error.printf("Java 25 or newer is required; detected Java %d.%n", javaFeature);
        error.flush();
        return 1;
    }

    private static int report(CommandLine commandLine, String message, int exitCode) {
        commandLine.getErr().println(message);
        commandLine.getErr().flush();
        return exitCode;
    }

    void main(String[] arguments) {
        System.exit(execute(arguments, Runtime.version().feature(), new PrintWriter(System.out, true), new PrintWriter(System.err, true)));
    }
}
