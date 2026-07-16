package dev.mcdevmcp.tools.statictool;

enum SearchType {
    CLASS, METHOD, FIELD, UNKNOWN;
    
    static SearchType from(TextArgument value) {
        if (value.isMissing()) {
            return null;
        }
        return switch (value.value()) {
            case "class" -> CLASS;
            case "method" -> METHOD;
            case "field" -> FIELD;
            default -> UNKNOWN;
        };
    }
}
