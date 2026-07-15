package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.support.JsonValues;

import java.util.Map;

record UnavailableToolArguments(Map<String, Object> values) {
    UnavailableToolArguments {
        values = JsonValues.freezeMap(values);
    }
}
