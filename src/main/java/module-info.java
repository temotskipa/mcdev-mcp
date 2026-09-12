open module dev.mcdevmcp {
    requires dev.mcdevmcp.mcp.tool.api;
    requires io.modelcontextprotocol.sdk.mcp.core;
    requires io.modelcontextprotocol.sdk.mcp.json.jackson3;
    requires info.picocli;
    requires com.h2database;
    requires org.jetbrains.java.decompiler;
    requires net.fabricmc.tinyremapper;
    requires net.fabricmc.mappingio;
    requires org.slf4j;
    requires org.slf4j.nop;
    requires java.net.http;
    requires java.sql;
    requires java.naming;
    requires java.logging;
    requires java.management;
    requires java.compiler;
    requires jdk.compiler;
}
