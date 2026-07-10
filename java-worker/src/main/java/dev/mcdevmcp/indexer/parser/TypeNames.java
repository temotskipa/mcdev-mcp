package dev.mcdevmcp.indexer.parser;

import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;

import java.util.ArrayList;
import java.util.List;

final class TypeNames {
    private TypeNames() {
    }
    
    static String simpleType(Tree type) {
        if (type == null) return "";
        return simpleType(type.toString());
    }
    
    static String simpleType(String rawType) {
        String type = stripGenerics(rawType).replace("...", "").replace("[]", "").trim();
        int dot = type.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < type.length()) {
            type = type.substring(dot + 1);
        }
        return type;
    }
    
    static List<String> modifiers(ModifiersTree modifiers) {
        List<String> result = new ArrayList<>();
        for (javax.lang.model.element.Modifier modifier : modifiers.getFlags()) {
            result.add(modifier.toString());
        }
        return result;
    }
    
    static void addIfMissing(List<String> values, String value) {
        if (!values.contains(value)) values.add(value);
    }
    
    private static String stripGenerics(String value) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                if (depth > 0) depth--;
            } else if (depth == 0) {
                out.append(ch);
            }
        }
        return out.toString();
    }
}