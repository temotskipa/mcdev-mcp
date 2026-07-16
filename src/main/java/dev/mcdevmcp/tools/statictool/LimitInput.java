package dev.mcdevmcp.tools.statictool;

record LimitInput(Number value) {
    static LimitInput fromWire(Object value) {
        return new LimitInput(value instanceof Number number ? number : null);
    }
}
