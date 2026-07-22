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

    static CompilerClasspath read(IndexRequest request) throws IOException, InterruptedException {
        Map<String, CompilerClassFile> classes = new LinkedHashMap<>();
        List<Path> entries = new ArrayList<>(request.classpath().size() + 1);
        entries.add(request.remappedJar());
        entries.addAll(request.classpath());
        for (Path entry : entries) {
            readJar(entry, request.cancellation(), classes);
        }
        return new CompilerClasspath(classes);
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
