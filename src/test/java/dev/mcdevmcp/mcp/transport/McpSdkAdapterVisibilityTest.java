package dev.mcdevmcp.mcp.transport;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSdkAdapterVisibilityTest {
    @Test
    void exposesOnlyTheDeliberateTransportFacade() {
        assertTrue(Arrays.stream(McpSdkAdapter.class.getDeclaredConstructors()).noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers())));

        var publicMethods = Arrays.stream(McpSdkAdapter.class.getDeclaredMethods()).filter(method -> Modifier.isPublic(method.getModifiers())).toList();
        assertEquals(java.util.List.of("startStdio"), publicMethods.stream().map(java.lang.reflect.Method::getName).toList());
        assertTrue(publicMethods.stream().allMatch(method -> Modifier.isStatic(method.getModifiers())));

        assertTrue(Arrays.stream(StdioServer.class.getDeclaredConstructors()).noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers())));
    }
}
