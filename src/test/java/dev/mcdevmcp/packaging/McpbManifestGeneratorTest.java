package dev.mcdevmcp.packaging;

import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.ToolMetadata;
import dev.mcdevmcp.support.JsonResourceReader;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpbManifestGeneratorTest {
    @Test
    void generatedCatalogUsesTheJavaToolMetadataAndConfiguredVersion() throws Exception {
        var mapper = McpJsonDefaults.getMapper();
        Map<String, Object> template = mapper.readValue(new JsonResourceReader(mapper).readText("/contracts/mcpb/manifest.json"), new TypeRef<>() {
        });
        ToolMetadata[] metadata = ToolCatalog.loadMetadata(mapper);

        Map<String, Object> manifest = McpbManifestGenerator.generatedRootManifest(template, "3.0.0", metadata);

        assertEquals("0.3", manifest.get("manifest_version"));
        assertEquals("3.0.0", manifest.get("version"));
        assertNull(manifest.get("server"));
        List<Map<String, Object>> tools = maps(manifest.get("tools"));
        assertEquals(metadata.length, tools.size());
        assertTrue(tools.stream().anyMatch(tool -> tool.get("name").equals("mc_record_video")));
        for (ToolMetadata tool : metadata) {
            Map<String, Object> generated = tools.stream().filter(candidate -> candidate.get("name").equals(tool.name())).findFirst().orElseThrow();
            assertEquals(tool.description(), generated.get("description"));
            assertEquals(tool.inputSchema(), generated.get("inputSchema"));
        }
    }

    @Test
    void stagingManifestAddsOnlyThePackerLauncherConfiguration() {
        Map<String, Object> staging = McpbManifestGenerator.stagingManifest(Map.of("manifest_version", "0.3", "name", "mcdev-mcp", "version", "3.0.0", "tools", List.of(Map.of("name", "mc_record_video", "description", "Record a video.", "inputSchema", Map.of("type", "object")))));

        assertEquals(List.of(Map.of("name", "mc_record_video", "description", "Record a video.")), staging.get("tools"));
        Map<String, Object> server = map(staging.get("server"));
        assertEquals("node", server.get("type"));
        assertEquals("bootstrap.cjs", server.get("entry_point"));
        assertEquals("node", map(server.get("mcp_config")).get("command"));
    }

    @Test
    void writerProducesIdenticalRootAndPackerCatalogs() throws Exception {
        var root = Files.createTempDirectory("mcpb-manifest");
        var template = root.resolve("template.json");
        Files.writeString(template, new JsonResourceReader(McpJsonDefaults.getMapper()).readText("/contracts/mcpb/manifest.json"));
        var rootManifest = root.resolve("manifest.json");
        var stagingManifest = root.resolve("stage/manifest.json");

        McpbManifestGenerator.generate(template, rootManifest, stagingManifest, "3.0.0");

        var mapper = McpJsonDefaults.getMapper();
        Map<String, Object> generatedRoot = mapper.readValue(Files.readString(rootManifest), new TypeRef<>() {
        });
        Map<String, Object> generatedStaging = mapper.readValue(Files.readString(stagingManifest), new TypeRef<>() {
        });
        assertEquals("3.0.0", generatedRoot.get("version"));
        assertEquals(generatedRoot.get("name"), generatedStaging.get("name"));
        assertFalse(generatedRoot.containsKey("server"));
        assertTrue(generatedStaging.containsKey("server"));
    }

    @Test
    void repeatedGenerationProducesIdenticalBytesAndTemplateOrder() throws Exception {
        var root = Files.createTempDirectory("mcpb-manifest-reproducibility");
        var template = root.resolve("template.json");
        Files.writeString(template, new JsonResourceReader(McpJsonDefaults.getMapper()).readText("/contracts/mcpb/manifest.json"));
        var firstRoot = root.resolve("first/manifest.json");
        var firstStaging = root.resolve("first/staging/manifest.json");
        var secondRoot = root.resolve("second/manifest.json");
        var secondStaging = root.resolve("second/staging/manifest.json");

        McpbManifestGenerator.generate(template, firstRoot, firstStaging, "3.0.0");
        McpbManifestGenerator.generate(template, secondRoot, secondStaging, "3.0.0");

        assertArrayEquals(Files.readAllBytes(firstRoot), Files.readAllBytes(secondRoot));
        assertArrayEquals(Files.readAllBytes(firstStaging), Files.readAllBytes(secondStaging));
        Map<String, Object> generated = McpJsonDefaults.getMapper().readValue(Files.readString(firstRoot), new TypeRef<>() {
        });
        assertEquals(List.of("manifest_version", "name", "display_name", "description", "author", "version", "tools", "user_config"), List.copyOf(generated.keySet()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> maps(Object value) {
        assertInstanceOf(List.class, value);
        return ((List<?>) value).stream().map(McpbManifestGeneratorTest::map).toList();
    }
}
