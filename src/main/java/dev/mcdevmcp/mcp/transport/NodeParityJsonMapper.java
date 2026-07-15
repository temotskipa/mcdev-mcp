package dev.mcdevmcp.mcp.transport;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.util.Objects;

/**
 * Narrows two SDK 2.0 response differences at the typed JSON-RPC boundary.
 */
final class NodeParityJsonMapper implements McpJsonMapper {
    private static final String UNKNOWN_TOOL_PREFIX = "Tool not found: ";
    
    private final McpJsonMapper delegate;
    
    NodeParityJsonMapper(McpJsonMapper delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }
    
    @Override
    public <T> T readValue(String content, Class<T> type) throws IOException {
        return delegate.readValue(content, type);
    }
    
    @Override
    public <T> T readValue(byte[] content, Class<T> type) throws IOException {
        return delegate.readValue(content, type);
    }
    
    @Override
    public <T> T readValue(String content, TypeRef<T> type) throws IOException {
        return delegate.readValue(content, type);
    }
    
    @Override
    public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
        return delegate.readValue(content, type);
    }
    
    @Override
    public <T> T convertValue(Object value, Class<T> type) {
        return delegate.convertValue(value, type);
    }
    
    @Override
    public <T> T convertValue(Object value, TypeRef<T> type) {
        return delegate.convertValue(value, type);
    }
    
    @Override
    public String writeValueAsString(Object value) throws IOException {
        return delegate.writeValueAsString(adaptResponse(value));
    }
    
    @Override
    public byte[] writeValueAsBytes(Object value) throws IOException {
        return delegate.writeValueAsBytes(adaptResponse(value));
    }
    
    private Object adaptResponse(Object value) {
        if (!(value instanceof McpSchema.JSONRPCResponse(
                String jsonrpc, Object id, Object result, McpSchema.JSONRPCResponse.JSONRPCError error
        ))) {
            return value;
        }
        if (result instanceof McpSchema.InitializeResult initializeResult) {
            return new McpSchema.JSONRPCResponse(jsonrpc, id, withoutSdkLoggingCapability(initializeResult), null);
        }
        if (error != null && error.data() instanceof String message && message.startsWith(UNKNOWN_TOOL_PREFIX)) {
            var callResult = McpSchema.CallToolResult.builder().addTextContent("Unknown tool: " + message.substring(UNKNOWN_TOOL_PREFIX.length())).isError(true).build();
            return new McpSchema.JSONRPCResponse(jsonrpc, id, callResult, null);
        }
        return value;
    }
    
    private McpSchema.InitializeResult withoutSdkLoggingCapability(McpSchema.InitializeResult result) {
        McpSchema.ServerCapabilities capabilities = result.capabilities();
        var nodeCapabilities = new McpSchema.ServerCapabilities(capabilities.completions(), capabilities.experimental(), null, capabilities.prompts(), capabilities.resources(), capabilities.tools());
        return new McpSchema.InitializeResult(result.protocolVersion(), nodeCapabilities, result.serverInfo(), result.instructions(), result.meta());
    }
}
