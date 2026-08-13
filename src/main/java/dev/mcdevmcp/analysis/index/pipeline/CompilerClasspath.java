package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.classfile.ClassDescriptors;
import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.support.Cancellation;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

record CompilerClasspath(List<CompilerClassFile> classes) {
    private CompilerClasspath(Map<String, CompilerClassFile> classes) {
        TreeMap<String, CompilerClassFile> sorted = new TreeMap<>(classes);
        this(List.copyOf(sorted.values()));
    }

    private static final List<String> BUILTIN_ANNOTATIONS = List.of("org.jetbrains.annotations.Nullable", "org.jetbrains.annotations.NotNull", "org.jetbrains.annotations.Contract", "org.jetbrains.annotations.Range", "org.jetbrains.annotations.NonNls", "org.jetbrains.annotations.UnknownNullability", "org.jspecify.annotations.Nullable", "org.jspecify.annotations.NonNull", "org.jspecify.annotations.NullMarked", "org.jspecify.annotations.NullUnmarked", "javax.annotation.Nullable", "javax.annotation.Nonnull", "javax.annotation.CheckForNull", "javax.annotation.ParametersAreNonnullByDefault");

    static CompilerClasspath read(IndexRequest request) throws IOException, InterruptedException {
        Map<String, CompilerClassFile> classes = new LinkedHashMap<>();
        List<Path> entries = new ArrayList<>(request.classpath().size() + 1);
        entries.add(request.remappedJar());
        entries.addAll(request.classpath());
        for (Path entry : entries) {
            readJar(entry, request.cancellation(), classes);
        }
        registerBuiltinAnnotations(classes);
        return new CompilerClasspath(classes);
    }

    private static void registerBuiltinAnnotations(Map<String, CompilerClassFile> classes) {
        ClassFile classFile = ClassFile.of();
        for (String binaryName : BUILTIN_ANNOTATIONS) {
            if (!classes.containsKey(binaryName)) {
                byte[] bytes = classFile.build(java.lang.constant.ClassDesc.of(binaryName), clb -> {
                    clb.withFlags(java.lang.reflect.AccessFlag.PUBLIC, java.lang.reflect.AccessFlag.INTERFACE, java.lang.reflect.AccessFlag.ANNOTATION, java.lang.reflect.AccessFlag.ABSTRACT);
                    clb.withInterfaceSymbols(java.lang.constant.ClassDesc.of("java.lang.annotation.Annotation"));
                    if (binaryName.equals("org.jetbrains.annotations.Contract")) {
                        clb.withMethod("value", java.lang.constant.MethodTypeDesc.of(java.lang.constant.ClassDesc.of("java.lang.String")), java.lang.reflect.AccessFlag.PUBLIC.mask() | java.lang.reflect.AccessFlag.ABSTRACT.mask(), mb -> {});
                        clb.withMethod("pure", java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_boolean), java.lang.reflect.AccessFlag.PUBLIC.mask() | java.lang.reflect.AccessFlag.ABSTRACT.mask(), mb -> {});
                    } else if (binaryName.equals("org.jetbrains.annotations.Range")) {
                        clb.withMethod("from", java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_long), java.lang.reflect.AccessFlag.PUBLIC.mask() | java.lang.reflect.AccessFlag.ABSTRACT.mask(), mb -> {});
                        clb.withMethod("to", java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_long), java.lang.reflect.AccessFlag.PUBLIC.mask() | java.lang.reflect.AccessFlag.ABSTRACT.mask(), mb -> {});
                    } else if (binaryName.equals("org.jetbrains.annotations.NonNls") || binaryName.equals("org.jetbrains.annotations.UnknownNullability")) {
                        clb.withMethod("value", java.lang.constant.MethodTypeDesc.of(java.lang.constant.ClassDesc.of("java.lang.String")), java.lang.reflect.AccessFlag.PUBLIC.mask() | java.lang.reflect.AccessFlag.ABSTRACT.mask(), mb -> {});
                    }
                });
                String packageName = binaryName.contains(".") ? binaryName.substring(0, binaryName.lastIndexOf('.')) : "";
                classes.put(binaryName, new CompilerClassFile(binaryName, packageName, bytes));
            }
        }
    }

    private static void readJar(Path jar, Cancellation cancellation, Map<String, CompilerClassFile> classes) throws IOException, InterruptedException {
        ClassFile classFile = ClassFile.of();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> entries = zip.stream().filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class")).sorted(Comparator.comparing(ZipEntry::getName)).toList();
            for (ZipEntry entry : entries) {
                cancellation.throwIfCancelled();
                byte[] bytes;
                try (var input = zip.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                var model = classFile.parse(bytes);
                if (model.isModuleInfo()) {
                    continue;
                }
                String binaryName = ClassDescriptors.binaryName(model.thisClass().asSymbol());
                String packageName = binaryName.contains(".") ? binaryName.substring(0, binaryName.lastIndexOf('.')) : "";
                classes.putIfAbsent(binaryName, new CompilerClassFile(binaryName, packageName, bytes));
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Unable to read compiler classpath entry " + jar, exception);
        }
    }
}
