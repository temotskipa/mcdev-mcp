package dev.mcdevmcp.benchmark;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Objects;

/**
 * Runtime identity captured inside each measurement JVM.
 */
public record BenchmarkRuntimeMetadata(int javaFeature, String vendor, String javaVersion, String runtimeVersion, String vmName, String vmVersion, String vmFlags, List<String> garbageCollectors) {
    public BenchmarkRuntimeMetadata {
        vendor = Objects.requireNonNull(vendor, "vendor");
        javaVersion = Objects.requireNonNull(javaVersion, "javaVersion");
        runtimeVersion = Objects.requireNonNull(runtimeVersion, "runtimeVersion");
        vmName = Objects.requireNonNull(vmName, "vmName");
        vmVersion = Objects.requireNonNull(vmVersion, "vmVersion");
        vmFlags = Objects.requireNonNull(vmFlags, "vmFlags");
        garbageCollectors = List.copyOf(garbageCollectors);
    }

    public static BenchmarkRuntimeMetadata current() {
        return new BenchmarkRuntimeMetadata(Runtime.version().feature(), System.getProperty("java.vendor"), System.getProperty("java.version"), Runtime.version().toString(), System.getProperty("java.vm.name"), System.getProperty("java.vm.version"), String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()), ManagementFactory.getGarbageCollectorMXBeans().stream().map(java.lang.management.MemoryManagerMXBean::getName).sorted().toList());
    }
}
