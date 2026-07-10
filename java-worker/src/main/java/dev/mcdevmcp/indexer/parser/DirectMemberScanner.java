package dev.mcdevmcp.indexer.parser;

import com.sun.source.tree.*;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import dev.mcdevmcp.indexer.model.FieldInfo;
import dev.mcdevmcp.indexer.model.MethodInfo;
import dev.mcdevmcp.indexer.model.ParamInfo;

import java.util.ArrayList;
import java.util.List;

final class DirectMemberScanner extends TreeScanner<Void, Void> {
    private final List<FieldInfo> fields = new ArrayList<>();
    private final List<MethodInfo> methods = new ArrayList<>();
    private final CompilationUnitTree unit;
    private final boolean interfaceMember;
    private final SourcePositions positions;
    private final LineMap lineMap;
    
    DirectMemberScanner(CompilationUnitTree unit, String kind, SourcePositions positions, LineMap lineMap) {
        this.unit = unit;
        this.interfaceMember = "interface".equals(kind);
        this.positions = positions;
        this.lineMap = lineMap;
    }
    
    List<FieldInfo> fields() {
        return fields;
    }
    
    List<MethodInfo> methods() {
        return methods;
    }
    
    @Override
    public Void visitVariable(VariableTree variable, Void unused) {
        fields.add(fieldInfo(variable));
        return null;
    }
    
    @Override
    public Void visitMethod(MethodTree method, Void unused) {
        if (!"<init>".contentEquals(method.getName())) {
            methods.add(methodInfo(method));
        }
        return null;
    }
    
    @Override
    public Void visitBlock(BlockTree block, Void unused) {
        return null;
    }
    
    @Override
    public Void visitClass(ClassTree nested, Void unused) {
        return null;
    }
    
    private FieldInfo fieldInfo(VariableTree variable) {
        List<String> modifiers = TypeNames.modifiers(variable.getModifiers());
        if (interfaceMember) {
            TypeNames.addIfMissing(modifiers, "public");
            TypeNames.addIfMissing(modifiers, "static");
            TypeNames.addIfMissing(modifiers, "final");
        }
        return new FieldInfo(variable.getName().toString(), TypeNames.simpleType(variable.getType()), modifiers);
    }
    
    private MethodInfo methodInfo(MethodTree method) {
        List<ParamInfo> params = new ArrayList<>();
        for (VariableTree param : method.getParameters()) {
            params.add(new ParamInfo(param.getName().toString(), TypeNames.simpleType(param.getType())));
        }
        
        long start = positions.getStartPosition(unit, method);
        long end = positions.getEndPosition(unit, method);
        long lineStart = start < 0 ? 0 : lineMap.getLineNumber(start);
        long lineEnd = end < 0 ? lineStart : lineMap.getLineNumber(end);
        
        return new MethodInfo(method.getName().toString(), method.getReturnType() == null ? "" : TypeNames.simpleType(method.getReturnType()), params, TypeNames.modifiers(method.getModifiers()), lineStart, lineEnd);
    }
}
