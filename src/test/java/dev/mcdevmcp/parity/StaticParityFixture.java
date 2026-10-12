package dev.mcdevmcp.parity;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.callgraph.CallgraphBundleTestSupport;
import dev.mcdevmcp.storage.callgraph.CallgraphDataRecord;
import dev.mcdevmcp.storage.h2.SymbolIndexData;
import dev.mcdevmcp.storage.h2.SymbolIndexFixture;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;


final class StaticParityFixture {
    static final MinecraftVersion VERSION = new MinecraftVersion("1.21.5");
    private static final Duration NODE_DATABASE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration NODE_DATABASE_TERMINATION_TIMEOUT = Duration.ofSeconds(5);
    private static final String PARITY_SOURCE = """
                                                package example;
                                                public class Parity {
                                                    private int Needle;
                                                    public void needle(String arg) { }
                                                    public void run() { }
                                                }
                                                """;
    private static final String NODE_DATABASE_SCRIPT = """
                                                       import initSqlJs from 'sql%s';
                                                       import fs from 'node:fs';
                                                       import path from 'node:path';
                                                       const output = process.argv[1];
                                                       const rows = JSON.parse(fs.readFileSync(0, 'utf8'));
                                                       const SQL = await initSqlJs({ locateFile: file => path.resolve('node_modules', 'sql%s', 'dist', file) });
                                                       const database = new SQL.Database();
                                                       database.exec('CREATE TABLE calls (id INTEGER PRIMARY KEY, caller_class TEXT, caller_method TEXT, caller_desc TEXT, callee_class TEXT, callee_method TEXT, callee_desc TEXT, line_number INTEGER); CREATE INDEX idx_callee ON calls(callee_class, callee_method); CREATE INDEX idx_caller ON calls(caller_class, caller_method);');
                                                       const insert = database.prepare('INSERT INTO calls VALUES (?, ?, ?, ?, ?, ?, ?, ?)');
                                                       for (const row of rows) insert.run(row);
                                                       insert.free();
                                                       fs.mkdirSync(path.dirname(output), { recursive: true });
                                                       fs.writeFileSync(output, Buffer.from(database.export()));
                                                       database.close();
                                                       """.formatted(".js", ".js");

    private StaticParityFixture() {
    }

    static void prepareNode(Path processRoot, NodeOracleMaterializer oracle) throws Exception {
        PlatformPaths paths = paths(processRoot);
        writeSources(paths);
        writeNodeSymbolIndex(paths);
        writeNodeCallgraph(paths, oracle);
    }

    static void prepareJava(Path processRoot) throws Exception {
        PlatformPaths paths = paths(processRoot);
        writeSources(paths);
        writeJavaSymbolIndex(paths);
        writeJavaCallgraph(paths);
    }

    private static PlatformPaths paths(Path processRoot) {
        Map<String, String> environment = Map.of("LOCALAPPDATA", processRoot.resolve("local-app-data").toString(), "XDG_CACHE_HOME", processRoot.resolve("xdg-cache").toString());
        return PlatformPaths.forEnvironment(System.getProperty("os.name"), environment, processRoot.resolve("home"));
    }

    private static void writeSources(PlatformPaths paths) throws IOException {
        Path example = paths.sourceRoot(VERSION).resolve("example");
        Path bulk = paths.sourceRoot(VERSION).resolve("bulk");
        Files.createDirectories(example);
        Files.createDirectories(bulk);
        Files.writeString(example.resolve("Parity.java"), PARITY_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(example.resolve("Other.java"), "package example;\npublic class Other { }\n", StandardCharsets.UTF_8);
        Files.writeString(example.resolve("Child.java"), "package example;\npublic class Child extends Parity { }\n", StandardCharsets.UTF_8);
        Files.writeString(example.resolve("ChildTwo.java"), "package example;\npublic class ChildTwo extends Parity { }\n", StandardCharsets.UTF_8);
        Files.writeString(example.resolve("Marker.java"), "package example;\npublic interface Marker { }\n", StandardCharsets.UTF_8);
        Files.writeString(example.resolve("Impl.java"), "package example;\npublic class Impl implements Marker { }\n", StandardCharsets.UTF_8);
        Files.writeString(bulk.resolve("HitOne.java"), "package bulk;\npublic class HitOne { }\n", StandardCharsets.UTF_8);
        Files.writeString(bulk.resolve("HitTwo.java"), "package bulk;\npublic class HitTwo { }\n", StandardCharsets.UTF_8);
    }

    private static void writeNodeSymbolIndex(PlatformPaths paths) throws IOException {
        Path index = paths.cacheRoot().resolve("index").resolve(VERSION.value());
        Files.createDirectories(index.resolve("minecraft"));
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("minecraftVersion", VERSION.value());
        manifest.put("fabricApiVersion", null);
        manifest.put("generated", "2026-07-29T00:00:00Z");
        manifest.put("indexerVersion", "regex");
        manifest.put("packages", Map.of("minecraft", List.of("example", "bulk"), "fabric", List.of()));
        writeJson(index.resolve("manifest.json"), manifest);

        var exampleClasses = new LinkedHashMap<String, Object>();
        exampleClasses.put("Parity", nodeClass("class", null, List.of(), "example/Parity.java", List.of(Map.of("name", "Needle", "type", "int", "modifiers", List.of("private"))), List.of(nodeMethod("needle", List.of(Map.of("name", "arg", "type", "String")), 4), nodeMethod("run", List.of(), 5))));
        exampleClasses.put("Other", nodeClass("class", null, List.of(), "example/Other.java", List.of(), List.of()));
        exampleClasses.put("Child", nodeClass("class", "example.Parity", List.of(), "example/Child.java", List.of(), List.of()));
        exampleClasses.put("ChildTwo", nodeClass("class", "example.Parity", List.of(), "example/ChildTwo.java", List.of(), List.of()));
        exampleClasses.put("Marker", nodeClass("interface", null, List.of(), "example/Marker.java", List.of(), List.of()));
        exampleClasses.put("Impl", nodeClass("class", null, List.of("example.Marker"), "example/Impl.java", List.of(), List.of()));
        writeJson(index.resolve("minecraft/example.json"), Map.of("package", "example", "classes", exampleClasses));

        var bulkClasses = new LinkedHashMap<String, Object>();
        bulkClasses.put("HitOne", nodeClass("class", null, List.of(), "bulk/HitOne.java", List.of(), List.of()));
        bulkClasses.put("HitTwo", nodeClass("class", null, List.of(), "bulk/HitTwo.java", List.of(), List.of()));
        writeJson(index.resolve("minecraft/bulk.json"), Map.of("package", "bulk", "classes", bulkClasses));
    }

    private static Map<String, Object> nodeClass(String kind, String superclass, List<String> interfaces, String sourcePath, List<Map<String, Object>> fields, List<Map<String, Object>> methods) {
        var value = new LinkedHashMap<String, Object>();
        value.put("kind", kind);
        value.put("super", superclass);
        value.put("interfaces", interfaces);
        value.put("fields", fields);
        value.put("methods", methods);
        value.put("sourcePath", sourcePath);
        return value;
    }

    private static Map<String, Object> nodeMethod(String name, List<Map<String, String>> parameters, int line) {
        return Map.of("name", name, "returnType", "void", "params", parameters, "modifiers", List.of("public"), "lineStart", line, "lineEnd", line);
    }

    private static void writeJson(Path path, Object value) throws IOException {
        Files.write(path, McpJsonDefaults.getMapper().writeValueAsBytes(value));
    }

    private static void writeJavaSymbolIndex(PlatformPaths paths) throws Exception {
        List<SymbolIndexData.TypeRow> types = List.of(type(1, 1, "example.Parity", "Parity", "class", null, List.of(), "example/Parity.java", 6), type(2, 1, "example.Other", "Other", "class", null, List.of(), "example/Other.java", 2), type(3, 1, "example.Child", "Child", "class", "example.Parity", List.of(), "example/Child.java", 2), type(4, 1, "example.ChildTwo", "ChildTwo", "class", "example.Parity", List.of(), "example/ChildTwo.java", 2), type(5, 1, "example.Marker", "Marker", "interface", null, List.of(), "example/Marker.java", 2), type(6, 1, "example.Impl", "Impl", "class", null, List.of("example.Marker"), "example/Impl.java", 2), type(7, 2, "bulk.HitOne", "HitOne", "class", null, List.of(), "bulk/HitOne.java", 2), type(8, 2, "bulk.HitTwo", "HitTwo", "class", null, List.of(), "bulk/HitTwo.java", 2));
        List<SymbolIndexData.MethodRow> methods = List.of(new SymbolIndexData.MethodRow(1, 1, 0, "needle", "(Ljava/lang/String;)V", "void", "public", false, 0, 0, 4, 4), new SymbolIndexData.MethodRow(2, 1, 1, "run", "()V", "void", "public", false, 0, 0, 5, 5));
        SymbolIndexFixture.write(paths.symbolDatabase(VERSION), VERSION, paths.sourceRoot(VERSION), "0".repeat(64), Instant.parse("2026-07-29T00:00:00Z"), List.of(SymbolIndexFixture.minecraftPackage(1, "example"), SymbolIndexFixture.minecraftPackage(2, "bulk")), types, List.of(new SymbolIndexData.FieldRow(1, 1, 0, "Needle", "int", "private", 0, 0, 3, 3)), methods, List.of(new SymbolIndexData.ParameterRow(1, 1, 0, "arg", "String", false, 0, 0, 4, 4)));
    }

    private static SymbolIndexData.TypeRow type(long id, long packageId, String binaryName, String simpleName, String kind, String superclass, List<String> interfaces, String sourcePath, int endLine) {
        return SymbolIndexFixture.minecraftType(id, packageId, binaryName, simpleName, kind, superclass, interfaces, sourcePath, 2, endLine);
    }

    private static void writeJavaCallgraph(PlatformPaths paths) throws Exception {
        CallgraphBundleTestSupport.publish(paths.callgraphBundle(VERSION), VERSION, callgraphRecords());
    }

    private static List<CallgraphDataRecord> callgraphRecords() {
        List<CallgraphDataRecord> records = new ArrayList<>();
        records.add(edge(1, "caller.Described", "entry", "()V", "target.Target", "hit", "(I)V", 11));
        records.add(edge(2, "caller.Legacy", "entry", null, "target.Target", "hit", null, 12));
        records.add(edge(3, "origin.Origin", "dispatch", "()V", "callee.First", "work", "(Ljava/lang/String;)V", 21));
        records.add(edge(4, "origin.Origin", "dispatch", null, "callee.Second", "stop", null, 0));
        records.add(edge(5, "bulk.RefA", "call", null, "bulk.Target", "hit", null, 31));
        records.add(edge(6, "bulk.RefB", "call", null, "bulk.Target", "hit", null, 32));
        return List.copyOf(records);
    }

    private static CallgraphDataRecord edge(long id, String callerClass, String callerMethod, String callerDescriptor, String calleeClass, String calleeMethod, String calleeDescriptor, Integer line) {
        return new CallgraphDataRecord(id, callerClass, callerMethod, callerDescriptor, calleeClass, calleeMethod, calleeDescriptor, line);
    }

    private static void writeNodeCallgraph(PlatformPaths paths, NodeOracleMaterializer oracle) throws Exception {
        Path database = paths.versionCache(VERSION).resolve("callgraph/callgraph.db");
        List<List<Object>> rows = callgraphRecords().stream().map(record -> Arrays.<Object>asList(record.edgeId(), record.callerClass(), record.callerMethod(), record.callerDescriptor(), record.calleeClass(), record.calleeMethod(), record.calleeDescriptor(), record.lineNumber())).toList();
        String encodedRows = McpJsonDefaults.getMapper().writeValueAsString(rows);
        ProcessBuilder builder = oracle.nodeProcess("--input-type=module", "-e", NODE_DATABASE_SCRIPT, database.toString());
        Path output = paths.cacheRoot().resolve("node-callgraph-builder.log");
        Files.createDirectories(output.getParent());
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        Process process = builder.start();
        try (var standardInput = process.getOutputStream()) {
            standardInput.write(encodedRows.getBytes(StandardCharsets.UTF_8));
        }
        boolean finished;
        try {
            finished = process.waitFor(NODE_DATABASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            terminateAfterInterruption(process, exception);
            Thread.currentThread().interrupt();
            throw exception;
        }
        if (!finished) {
            terminateProcessTree(process);
            throw new IOException("Timed out creating Node parity callgraph database after " + NODE_DATABASE_TIMEOUT);
        }
        if (process.exitValue() != 0) {
            throw new IOException("Node parity callgraph database builder failed (" + process.exitValue() + "):\n" + Files.readString(output, StandardCharsets.UTF_8));
        }
        if (!Files.isRegularFile(database)) {
            throw new IOException("Node parity callgraph database builder produced no database: " + database);
        }
        Files.deleteIfExists(output);
    }

    private static void terminateAfterInterruption(Process process, InterruptedException interruption) {
        try {
            terminateProcessTree(process);
        } catch (IOException | InterruptedException cleanupFailure) {
            interruption.addSuppressed(cleanupFailure);
        }
    }

    private static void terminateProcessTree(Process process) throws IOException, InterruptedException {
        Set<ProcessHandle> tree = snapshotProcessTree(process);
        destroy(tree, false);
        if (awaitTermination(tree, NODE_DATABASE_TERMINATION_TIMEOUT.dividedBy(2))) {
            return;
        }

        tree.addAll(snapshotProcessTree(process));
        destroy(tree, true);
        if (!awaitTermination(tree, NODE_DATABASE_TERMINATION_TIMEOUT.dividedBy(2))) {
            List<Long> survivors = tree.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).sorted().toList();
            throw new IOException("Failed to terminate Node parity callgraph database process tree: " + survivors);
        }
    }

    private static Set<ProcessHandle> snapshotProcessTree(Process process) {
        try (Stream<ProcessHandle> descendants = process.descendants()) {
            var tree = new LinkedHashSet<ProcessHandle>();
            descendants.forEach(tree::add);
            tree.add(process.toHandle());
            return tree;
        }
    }

    private static void destroy(Set<ProcessHandle> tree, boolean forcibly) {
        List<ProcessHandle> childFirst = new ArrayList<>(tree);
        childFirst.reversed().forEach(handle -> {
            if (!handle.isAlive()) {
                return;
            }
            if (forcibly) {
                handle.destroyForcibly();
            }
            else {
                handle.destroy();
            }
        });
    }

    private static boolean awaitTermination(Set<ProcessHandle> tree, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (tree.stream().anyMatch(ProcessHandle::isAlive)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)));
        }
        return true;
    }
}