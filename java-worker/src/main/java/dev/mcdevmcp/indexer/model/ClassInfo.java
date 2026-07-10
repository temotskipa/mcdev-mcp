package dev.mcdevmcp.indexer.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public record ClassInfo(String kind, @SerializedName("super") String superClass, List<String> interfaces,
                        List<FieldInfo> fields, List<MethodInfo> methods, String sourcePath) {
}