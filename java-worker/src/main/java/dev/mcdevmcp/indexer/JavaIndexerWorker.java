package dev.mcdevmcp.indexer;

import dev.mcdevmcp.indexer.model.ParsedClass;
import dev.mcdevmcp.indexer.parser.JavaSourceParser;
import dev.mcdevmcp.indexer.protocol.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class JavaIndexerWorker {
    private static final JavaSourceParser PARSER = new JavaSourceParser();
    private static final SourceFile NO_FILE = new SourceFile("");
    
    private JavaIndexerWorker() {
    }
    
    static void main() throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                System.out.println(handleLine(line));
                System.out.flush();
            }
        }
    }
    
    private static String handleLine(String line) {
        Request request;
        try {
            request = ProtocolJson.parseRequest(line);
        } catch (RuntimeException e) {
            return ProtocolJson.toJson(new Response(0, List.of(), List.of(new Failure(NO_FILE, "Invalid request JSON: " + e.getMessage()))));
        }
        
        List<ParsedClass> parsed = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (SourceFile file : request.files()) {
            try {
                parsed.add(PARSER.parseFile(file));
            } catch (Exception e) {
                failures.add(new Failure(file, e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        return ProtocolJson.toJson(new Response(request.id(), parsed, failures));
    }
}