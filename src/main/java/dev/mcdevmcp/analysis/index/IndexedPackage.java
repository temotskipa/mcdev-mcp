package dev.mcdevmcp.analysis.index;

import dev.mcdevmcp.storage.model.FabricApiVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;

import java.util.Optional;

record IndexedPackage(long id, SourceNamespace namespace, Optional<FabricApiVersion> fabricApiVersion, String name) {
}
