package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.support.RequiredRuntime;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryManagerMXBean;
import java.util.List;
import java.util.Objects;

/**
 * Runtime identity captured inside each measurement JVM.
 */
public record BenchmarkRuntimeMetadata(int javaFeature, String vendor, String javaVersion, String runtimeVersion, String vmName, String vmVersion, String vmFlags, List<String> garbageCollectors, String osName, ProcessMemoryMetric memoryMetric) {
    public BenchmarkRuntimeMetadata {
        vendor = Objects.requireNonNull(vendor, "vendor");
        javaVersion = Objects.requireNonNull(javaVersion, "javaVersion");
        runtimeVersion = Objects.requireNonNull(runtimeVersion, "runtimeVersion");
        vmName = Objects.requireNonNull(vmName, "vmName");
        vmVersion = Objects.requireNonNull(vmVersion, "vmVersion");
        vmFlags = Objects.requireNonNull(vmFlags, "vmFlags");
        garbageCollectors = List.copyOf(garbageCollectors);
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(memoryMetric, "memoryMetric");
    }

    public static BenchmarkRuntimeMetadata current() {
        int feature = Runtime.version().feature();
        if (feature != RequiredRuntime.FEATURE) {
            throw new IllegalStateException(RequiredRuntime.previewRequirement() + "; detected Java " + feature);
        }
        return new BenchmarkRuntimeMetadata(feature, System.getProperty("java.vendor"), System.getProperty("java.version"), Runtime.version().toString(), System.getProperty("java.vm.name"), System.getProperty("java.vm.version"), String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()), ManagementFactory.getGarbageCollectorMXBeans().stream().map(MemoryManagerMXBean::getName).sorted().toList(), System.getProperty("os.name"), ProcessPeakMemory.metric());
    }
}
