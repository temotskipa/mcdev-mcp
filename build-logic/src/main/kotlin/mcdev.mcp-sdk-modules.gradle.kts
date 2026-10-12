plugins {
    id("org.gradlex.extra-java-module-info")
}

extraJavaModuleInfo {
    failOnMissingModuleInfo.set(false)
    failOnAutomaticModules.set(false)

    // The snapshot already publishes these module names. The generated descriptors
    // exist so the jars require the modules their bytecode uses.
    module("io.modelcontextprotocol.sdk:mcp-core", "io.modelcontextprotocol.sdk.mcp.core") {
        exportAllPackages()
        requiresTransitive("com.fasterxml.jackson.annotation")
        requiresStatic("jakarta.servlet")
        requiresTransitive("java.net.http")
        requiresTransitive("org.reactivestreams")
        requiresTransitive("org.slf4j")
        requiresTransitive("reactor.core")
        uses("io.modelcontextprotocol.json.McpJsonMapperSupplier")
        uses("io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier")
    }

    module("io.modelcontextprotocol.sdk:mcp-json-jackson3", "io.modelcontextprotocol.sdk.mcp.json.jackson3") {
        exports("io.modelcontextprotocol.json.jackson3")
        exports("io.modelcontextprotocol.json.schema.jackson3")
        requires("com.networknt.schema")
        requiresTransitive("io.modelcontextprotocol.sdk.mcp.core")
        requires("org.slf4j")
        requires("tools.jackson.core")
        requiresTransitive("tools.jackson.databind")
    }
}
