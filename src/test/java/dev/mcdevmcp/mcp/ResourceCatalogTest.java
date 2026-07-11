package dev.mcdevmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceCatalogTest {
    private static String contractText(String name) throws IOException {
        return contract(name).getAsJsonObject("result").getAsJsonArray("contents").get(0).getAsJsonObject().get("text").getAsString();
    }

    private static JsonObject contract(String name) throws IOException {
        try (var input = ResourceCatalogTest.class.getResourceAsStream("/contracts/mcp/" + name)) {
            if (input == null) {
                throw new IOException("Missing contract: " + name);
            }
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void resourceListMatchesTheNodeContract() throws Exception {
        var catalog = new ResourceCatalog();
        var actual = new JsonArray();
        for (var definition : catalog.definitions()) {
            var resource = new JsonObject();
            resource.addProperty("uri", definition.uri().toString());
            resource.addProperty("name", definition.name());
            resource.addProperty("title", definition.title());
            resource.addProperty("description", definition.description());
            resource.addProperty("mimeType", definition.mimeType());
            actual.add(resource);
        }

        assertEquals(ToolCatalogContractTest.normalize(contract("resources-list.json").getAsJsonObject("result").getAsJsonArray("resources")), ToolCatalogContractTest.normalize(actual));
    }

    @Test
    void resourceContentsMatchTheNodeFixtures() throws Exception {
        var catalog = new ResourceCatalog();

        assertEquals(
                contractText("resource-python-scripting.json"),
                catalog.read(URI.create("mcdev://guides/python-scripting")).text());
        assertEquals(
                contractText("resource-dev-loop.json"),
                catalog.read(URI.create("mcdev://guides/dev-loop")).text());
    }

    @Test
    void unknownResourceUsesTheNodeErrorText() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceCatalog().read(URI.create("mcdev://guides/missing")));

        assertEquals("Unknown resource URI: mcdev://guides/missing", exception.getMessage());
    }
}
