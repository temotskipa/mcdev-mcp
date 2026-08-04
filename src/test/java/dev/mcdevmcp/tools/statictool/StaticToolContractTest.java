package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.CompleteToolBindings;
import dev.mcdevmcp.mcp.tool.ToolResult;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.h2.SymbolSchema;
import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class StaticToolContractTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.5");
    private final List<ExecutorService> catalogExecutors = new ArrayList<>();
    @TempDir
    Path temporaryDirectory;

    private static ClassSymbol symbol(Path sourcePath) {
        return new ClassSymbol(1, SourceNamespace.MINECRAFT, Optional.empty(), "alpha.Alpha", "alpha", "Alpha", javax.lang.model.element.ElementKind.CLASS, Optional.empty(), List.of(), sourcePath, 0, 0, 1, 1);
    }

    private static List<Map<String, Object>> jsonLines(String resource) throws Exception {
        try (var input = StaticToolContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            String contents = new String(Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            List<Map<String, Object>> documents = new ArrayList<>();
            int start = -1;
            int depth = 0;
            boolean quoted = false;
            boolean escaped = false;
            for (int index = 0; index < contents.length(); index++) {
                char character = contents.charAt(index);
                if (start < 0) {
                    if (Character.isWhitespace(character)) {
                        continue;
                    }
                    if (character != '{') {
                        throw new java.io.IOException("Expected a JSON object at offset " + index + " in " + resource);
                    }
                    start = index;
                }
                if (quoted) {
                    if (escaped) {
                        escaped = false;
                    }
                    else if (character == '\\') {
                        escaped = true;
                    }
                    else if (character == '"') {
                        quoted = false;
                    }
                    continue;
                }
                if (character == '"') {
                    quoted = true;
                }
                else if (character == '{' || character == '[') {
                    depth++;
                }
                else if (character == '}' || character == ']') {
                    depth--;
                    if (depth == 0) {
                        documents.add(McpJsonDefaults.getMapper().readValue(contents.substring(start, index + 1), new TypeRef<>() {
                        }));
                        start = -1;
                    }
                    else if (depth < 0) {
                        throw new java.io.IOException("Unbalanced JSON at offset " + index + " in " + resource);
                    }
                }
            }
            if (start >= 0) {
                throw new java.io.IOException("Incomplete JSON document in " + resource);
            }
            return List.copyOf(documents);
        }
    }

    private static String text(ToolCatalog catalog, String name, Map<String, Object> arguments) {
        ToolResult result = catalog.dispatch(name, arguments, Cancellation.none()).toCompletableFuture().join();
        return result.content().getFirst().text();
    }

    private static void createPrimaryDatabase(PlatformPaths paths, Path sourceRoot) throws Exception {
        Files.createDirectories(paths.indexRoot(VERSION));
        Path database = paths.symbolDatabase(VERSION);
        String base = database.toAbsolutePath().toString().substring(0, database.toString().length() - ".mv.db".length());
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + base + ";DB_CLOSE_ON_EXIT=FALSE")) {
            SymbolSchema.create(connection, VERSION, sourceRoot, "0".repeat(64), Instant.parse("2026-07-16T00:00:00Z"));
            insert(connection, "INSERT INTO packages(id, source_namespace, fabric_api_version, name) VALUES (1, 'minecraft', NULL, 'alpha'), (2, 'minecraft', NULL, 'beta'), (3, 'minecraft', NULL, 'gamma'), (4, 'minecraft', NULL, 'bulk'), (5, 'minecraft', NULL, 'hierarchy'), (6, 'fabric', '0.102.0+1.21.5', 'net.fabricmc.fabric.api')");
            try (var types = connection.prepareStatement("INSERT INTO types(id, package_id, source_namespace, fabric_api_version, binary_name, simple_name, kind, superclass_binary_name, source_path, start_offset, end_offset, start_line, end_line) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                 var interfaces = connection.prepareStatement("INSERT INTO type_interfaces(type_id, ordinal, interface_binary_name) VALUES (?, ?, ?)")) {
                type(types, 1, 1, "minecraft", null, "alpha.Alpha", "Alpha", "class", null, "alpha/Alpha.java", 2, 5);
                type(types, 2, 1, "minecraft", null, "alpha.ConstructorOnly", "ConstructorOnly", "class", null, "alpha/ConstructorOnly.java", 2, 3);
                type(types, 3, 2, "minecraft", null, "beta.Beta", "Beta", "class", "alpha.Alpha", "beta/Beta.java", 2, 2);
                type(types, 4, 3, "minecraft", null, "gamma.Gamma", "Gamma", "class", null, "gamma/Gamma.java", 2, 2);
                long id = 5;
                for (int index = 0; index <= 5000; index++, id++) {
                    type(types, id, 4, "minecraft", null, "bulk.Bulk%04d".formatted(index), "Bulk%04d".formatted(index), "class", null, "alpha/Alpha.java", 1, 1);
                }
                long root = id++;
                type(types, root, 5, "minecraft", null, "hierarchy.RootInterface", "RootInterface", "interface", null, "alpha/Alpha.java", 1, 1);
                for (int index = 0; index <= 250; index++, id++) {
                    type(types, id, 5, "minecraft", null, "hierarchy.Child%03d".formatted(index), "Child%03d".formatted(index), "class", "alpha.Alpha", "alpha/Alpha.java", 1, 1);
                }
                for (int index = 0; index <= 250; index++, id++) {
                    type(types, id, 5, "minecraft", null, "hierarchy.Impl%03d".formatted(index), "Impl%03d".formatted(index), "class", null, "alpha/Alpha.java", 1, 1);
                    interfaces.setLong(1, id);
                    interfaces.setInt(2, 0);
                    interfaces.setString(3, "hierarchy.RootInterface");
                    interfaces.addBatch();
                }
                type(types, id, 6, "fabric", "0.102.0+1.21.5", "net.fabricmc.fabric.api.FabricThing", "FabricThing", "class", null, "FabricThing.java", 1, 1);
                types.executeBatch();
                interfaces.executeBatch();
            }
            insert(connection, "INSERT INTO fields(id, type_id, ordinal, name, type, modifiers, start_offset, end_offset, start_line, end_line) VALUES (1, 1, 0, 'Needle', 'int', 'private', 27, 46, 3, 3)");
            insert(connection, "INSERT INTO methods(id, type_id, ordinal, name, descriptor, return_type, modifiers, constructor, start_offset, end_offset, start_line, end_line) VALUES (1, 1, 0, 'needle', '(Ljava/lang/String;)V', 'void', 'public', FALSE, 51, 85, 4, 4), (2, 1, 1, 'Needle', '()V', 'void', 'public', FALSE, 90, 112, 5, 5), (3, 2, 0, 'ConstructorOnly', '()V', NULL, 'public', TRUE, 44, 70, 3, 3)");
            insert(connection, "INSERT INTO parameters(id, method_id, ordinal, name, type, varargs, start_offset, end_offset, start_line, end_line) VALUES (1, 1, 0, 'arg', 'String', FALSE, 70, 80, 4, 4)");
            SymbolSchema.createIndexes(connection);
        }
    }

    private static void createOtherDatabase(PlatformPaths paths) throws Exception {
        MinecraftVersion otherVersion = new MinecraftVersion("1.21.6");
        Files.createDirectories(paths.indexRoot(otherVersion));
        Path database = paths.symbolDatabase(otherVersion);
        String base = database.toAbsolutePath().toString().substring(0, database.toString().length() - ".mv.db".length());
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + base + ";DB_CLOSE_ON_EXIT=FALSE")) {
            SymbolSchema.create(connection, otherVersion, paths.sourceRoot(otherVersion), "0".repeat(64), Instant.parse("2026-07-16T00:00:00Z"));
            insert(connection, "INSERT INTO packages(id, source_namespace, fabric_api_version, name) VALUES (1, 'minecraft', NULL, 'other')");
            insert(connection, "INSERT INTO types(id, package_id, source_namespace, fabric_api_version, binary_name, simple_name, kind, superclass_binary_name, source_path, start_offset, end_offset, start_line, end_line) VALUES (1, 1, 'minecraft', NULL, 'other.Other', 'Other', 'class', NULL, 'other/Other.java', 0, 37, 2, 2)");
            SymbolSchema.createIndexes(connection);
        }
    }

    private static void type(java.sql.PreparedStatement statement, long id, long packageId, String namespace, String fabricVersion, String binaryName, String simpleName, String kind, String superclass, String sourcePath, int startLine, int endLine) throws Exception {
        statement.setLong(1, id);
        statement.setLong(2, packageId);
        statement.setString(3, namespace);
        statement.setString(4, fabricVersion);
        statement.setString(5, binaryName);
        statement.setString(6, simpleName);
        statement.setString(7, kind);
        statement.setString(8, superclass);
        statement.setString(9, sourcePath);
        statement.setInt(10, 0);
        statement.setInt(11, 0);
        statement.setInt(12, startLine);
        statement.setInt(13, endLine);
        statement.addBatch();
    }

    private static void insert(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    @AfterEach
    void closeCatalogExecutors() {
        catalogExecutors.forEach(ExecutorService::close);
    }

    @Test
    void preservesFrozenStaticToolTextsForSuccessEmptyLimitsAndVersions() throws Exception {
        ToolCatalog catalog = catalog(fixture());

        assertEquals("No Minecraft version is currently set.\n\nSTOP and ask the USER which version they want to use, then call mc_version with action=\"set\".\nOr, provide a 'version' parameter in your tool call.\n\nTo see available versions, call mc_version with action=\"list\".", text(catalog, "mc_search", Map.of("query", "needle")));
        assertEquals("Active version set to 1.21.5.\nIndexed: yes\nCallgraph: no", text(catalog, "mc_version", Map.of("action", "set", "version", "1.21.5")));
        assertEquals("Found 3 result(s):\n[field] alpha.Alpha#Needle: private int Needle\n[method] alpha.Alpha#needle: public void needle(String arg) (line 4)\n[method] alpha.Alpha#Needle: public void Needle() (line 5)\nTotal: 3 result(s)", text(catalog, "mc_search", Map.of("query", "needle")));
        assertTrue(text(catalog, "mc_search", Map.of("query", "alp", "type", "class")).contains("[class] alpha.Alpha (1 fields, 2 methods)"));
        assertEquals("Found 6 package(s):\nalpha\nbeta\n... and 4+ more package(s) (showing first 2; pass a larger `limit` to see more)", text(catalog, "mc_list_packages", Map.of("limit", 2.8d)));
        assertEquals("Classes under \"ALPHA\":\nalpha.Alpha\n... and possibly more class(es) (showing first 1; pass a larger `limit` to see more)", text(catalog, "mc_list_classes", Map.of("packagePath", "ALPHA", "limit", 1L)));
        assertEquals("Subclasses of alpha.Alpha:\nbeta.Beta\n... and possibly more subclasses (showing first 1; pass a larger `limit` to see more)", text(catalog, "mc_find_hierarchy", Map.of("className", "alpha.Alpha", "direction", "subclasses", "limit", 1)));
        assertEquals("Method \"missing\" not found in class alpha.Alpha", text(catalog, "mc_get_method", Map.of("className", "alpha.Alpha", "methodName", "missing")));
        assertTrue(text(catalog, "mc_get_method", Map.of("className", "alpha.Alpha", "methodName", "NEEDLE")).startsWith("// Method: alpha.Alpha#needle\n"));
        assertTrue(text(catalog, "mc_get_class", Map.of("className", "alpha.Alpha", "view", "full")).endsWith("public void Needle() { }\n}\n"));
        assertEquals("Class not found: Alpha", text(catalog, "mc_get_class", Map.of("className", "Alpha")));
        assertEquals("Version 9.9.9 not initialized. STOP and ask the USER to run this command in their terminal:\n  java -jar mcdev-mcp-3.0.0.jar init -v 9.9.9\n\nThis will download, decompile, and index Minecraft 9.9.9 sources (including callgraph).", text(catalog, "mc_search", Map.of("query", "needle", "version", "9.9.9")));
    }

    @Test
    void acceptsNumbersFromDifferentWireNumberImplementationsAndReportsCapping() throws Exception {
        ToolCatalog catalog = catalog(fixture());
        text(catalog, "mc_version", Map.of("action", "set", "version", "1.21.5"));

        assertEquals("Found 3 result(s):\n[field] alpha.Alpha#Needle: private int Needle\n[method] alpha.Alpha#needle: public void needle(String arg) (line 4)\n[method] alpha.Alpha#Needle: public void Needle() (line 5)\n... and possibly more result(s) (showing first 3; pass a larger `limit` to see more)", text(catalog, "mc_search", Map.of("query", "needle", "limit", new java.math.BigDecimal("3.9"))));
        String capped = text(catalog, "mc_search", Map.of("query", "needle", "limit", new java.math.BigInteger("1001")));
        assertTrue(capped.endsWith("Total: 3 result(s)"));
        assertFalse(capped.contains("capped"));
        assertEquals("No results found for \"absent\"", text(catalog, "mc_search", Map.of("query", "absent", "limit", -1d)));
    }

    @Test
    void exposesOnlyTheEightStaticToolsAndNormalizesLargeLimitsWithoutOverflow() throws Exception {
        assertEquals(Set.of("mc_version", "mc_search", "mc_get_class", "mc_get_method", "mc_list_classes", "mc_list_packages", "mc_find_hierarchy", "mc_find_refs"), StaticToolModule.handlers(fixture()).keySet());
        LimitSpec limits = new LimitSpec(50, 1000);
        assertEquals(new NormalizedLimit(50, false, true), limits.normalize(new java.math.BigDecimal("-999999999999999999999999999")));
        assertEquals(new NormalizedLimit(1000, true, false), limits.normalize(new java.math.BigDecimal("999999999999999999999999999")));
        assertEquals(new NormalizedLimit(1000, false, false), limits.normalize(new java.math.BigDecimal("1000.999")));
    }

    @Test
    void rejectsUnsafeIndexedSourcePathsBeforeReadingThem() throws Exception {
        PlatformPaths paths = fixture();
        StaticToolSupport support = new StaticToolSupport(paths);
        ClassSymbol valid = symbol(Path.of("alpha/Alpha.java"));
        assertTrue(support.fullSource(VERSION, valid).startsWith("package alpha;"));
        assertUnsafeSource(support, symbol(Path.of("..", "outside.java")));
        assertUnsafeSource(support, symbol(temporaryDirectory.resolve("outside.java").toAbsolutePath()));

        Path outside = temporaryDirectory.resolve("outside.java");
        Files.writeString(outside, "outside");
        Path link = paths.sourceRoot(VERSION).resolve("alpha/escape.java");
        try {
            Files.createSymbolicLink(link, outside);
            assertUnsafeSource(support, symbol(Path.of("alpha/escape.java")));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
            // Windows developer-mode policy can disallow symlink creation in test environments.
        }
    }

    @Test
    void treatsMissingSourcesAsMissingSymbolsAndUnsafeIndexedPathsAsErrors() throws Exception {
        PlatformPaths paths = fixture();
        ToolCatalog catalog = catalog(paths);
        text(catalog, "mc_version", Map.of("action", "set", "version", "1.21.5"));
        Files.delete(paths.sourceRoot(VERSION).resolve("alpha/Alpha.java"));
        for (String view : List.of("summary", "fields", "methods", "full")) {
            assertEquals("Class not found: alpha.Alpha", text(catalog, "mc_get_class", Map.of("className", "alpha.Alpha", "view", view)));
        }
        assertEquals("Method \"needle\" not found in class alpha.Alpha", text(catalog, "mc_get_method", Map.of("className", "alpha.Alpha", "methodName", "needle")));

        Files.writeString(paths.sourceRoot(VERSION).resolve("alpha/Alpha.java"), "");
        assertEquals("Class not found: alpha.Alpha", text(catalog, "mc_get_class", Map.of("className", "alpha.Alpha")));
        assertEquals("Method \"needle\" not found in class alpha.Alpha", text(catalog, "mc_get_method", Map.of("className", "alpha.Alpha", "methodName", "needle")));

        Files.writeString(paths.sourceRoot(VERSION).resolve("alpha/Alpha.java"), "package alpha;\npublic class Alpha { }\n");
        String base = paths.symbolDatabase(VERSION).toAbsolutePath().toString().replaceFirst("\\.mv\\.db$", "");
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + base + ";DB_CLOSE_ON_EXIT=FALSE");
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE types SET source_path='../escape.java' WHERE binary_name='alpha.Alpha'");
        }
        ToolResult result = catalog.dispatch("mc_get_class", Map.of("className", "alpha.Alpha"), Cancellation.none()).toCompletableFuture().join();
        assertTrue(result.isError());
        assertEquals("Error executing mc_get_class: Unsafe indexed source path: " + Path.of("..", "escape.java"), result.content().getFirst().text());
    }

    @Test
    void publishesActivatedVersionToConcurrentReaders() throws Exception {
        StaticToolSupport support = new StaticToolSupport(fixture());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            support.activate(VERSION);
            assertEquals(VERSION, executor.submit(() -> support.active().orElseThrow()).get());
        }
    }

    @Test
    void returnsUnexpectedStorageFailuresAsToolErrors() {
        StaticToolSupport support = new StaticToolSupport(new PlatformPaths(temporaryDirectory));

        ToolResult ioFailure = support.execute("mc_get_class", () -> {
            throw new java.io.IOException("source denied");
        });
        ToolResult sqlFailure = support.execute("mc_search", () -> {
            throw new java.sql.SQLException("index corrupt");
        });

        assertTrue(ioFailure.isError());
        assertEquals("Error executing mc_get_class: source denied", ioFailure.content().getFirst().text());
        assertTrue(sqlFailure.isError());
        assertEquals("Error executing mc_search: index corrupt", sqlFailure.content().getFirst().text());
    }

    private void assertUnsafeSource(StaticToolSupport support, ClassSymbol symbol) {
        StaticToolException exception = assertThrows(StaticToolException.class, () -> support.fullSource(VERSION, symbol));
        assertEquals("Unsafe indexed source path: " + symbol.sourcePath(), exception.getMessage());
    }

    /**
     * Captured by the frozen Node process; provenance and hashes live in ignored task6-node-oracle evidence.
     */
    @Test
    void replaysTheFrozenNodeJsonlCorpusExactly() throws Exception {
        List<Map<String, Object>> requests = jsonLines("contracts/static-tools/requests.jsonl");
        List<Map<String, Object>> responses = jsonLines("contracts/static-tools/responses.jsonl");
        assertEquals(requests.size(), responses.size());
        ToolCatalog catalog = catalog(fixture());
        for (int index = 0; index < requests.size(); index++) {
            Map<String, Object> request = requests.get(index);
            Map<String, Object> expected = responses.get(index);
            Map<String, Object> params = McpJsonDefaults.getMapper().convertValue(McpJsonDefaults.getMapper().convertValue(request.get("request"), new TypeRef<Map<String, Object>>() {
            }).get("params"), new TypeRef<>() {
            });
            Map<String, Object> response = McpJsonDefaults.getMapper().convertValue(expected.get("response"), new TypeRef<>() {
            });
            Map<String, Object> result = McpJsonDefaults.getMapper().convertValue(response.get("result"), new TypeRef<>() {
            });
            ToolResult actual = catalog.dispatch((String) params.get("name"), McpJsonDefaults.getMapper().convertValue(params.get("arguments"), new TypeRef<>() {
            }), Cancellation.none()).toCompletableFuture().join();
            List<Map<String, Object>> content = McpJsonDefaults.getMapper().convertValue(result.get("content"), new TypeRef<>() {
            });
            assertEquals(content.getFirst().get("text"), actual.content().getFirst().text(), "corpus line " + index + " " + request.get("label"));
            assertEquals(Boolean.TRUE.equals(result.get("isError")), actual.isError(), "corpus line " + index + " " + request.get("label"));
        }
    }

    private ToolCatalog catalog(PlatformPaths paths) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        catalogExecutors.add(executor);
        return ToolCatalog.load(new AppEnvironment(Map.of()), CompleteToolBindings.including(McpJsonDefaults.getMapper(), StaticToolModule.handlers(paths)), McpJsonDefaults.getMapper(), executor);
    }

    private PlatformPaths fixture() throws Exception {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory);
        Path sourceRoot = paths.sourceRoot(VERSION);
        Path alpha = sourceRoot.resolve("alpha/Alpha.java");
        Path beta = sourceRoot.resolve("beta/Beta.java");
        Path gamma = sourceRoot.resolve("gamma/Gamma.java");
        Path constructorOnly = sourceRoot.resolve("alpha/ConstructorOnly.java");
        Files.createDirectories(alpha.getParent());
        Files.createDirectories(beta.getParent());
        Files.createDirectories(gamma.getParent());
        Files.writeString(alpha, "package alpha;\npublic class Alpha {\n    private int Needle;\n    public void needle(String arg) { }\n    public void Needle() { }\n}\n");
        Files.writeString(constructorOnly, "package alpha;\npublic class ConstructorOnly {\n    public ConstructorOnly() { }\n}\n");
        Files.writeString(beta, "package beta;\npublic class Beta extends alpha.Alpha { }\n");
        Files.writeString(gamma, "package gamma;\npublic class Gamma { }\n");
        Files.createDirectories(paths.sourceRoot(new MinecraftVersion("1.21.4")));
        Files.createDirectories(paths.sourceRoot(new MinecraftVersion("1.21.6")).resolve("other"));
        Files.writeString(paths.sourceRoot(new MinecraftVersion("1.21.6")).resolve("other/Other.java"), "package other;\npublic class Other { }\n");
        createPrimaryDatabase(paths, sourceRoot);
        createOtherDatabase(paths);
        return paths;
    }
}
