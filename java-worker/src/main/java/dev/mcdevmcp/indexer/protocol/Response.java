package dev.mcdevmcp.indexer.protocol;

import dev.mcdevmcp.indexer.model.ParsedClass;

import java.util.List;

public record Response(int id, List<ParsedClass> parsed, List<Failure> failures) {
}