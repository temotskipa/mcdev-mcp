package dev.mcdevmcp.analysis.index;

import java.lang.constant.ClassDesc;

final class DescriptorNames {
    private DescriptorNames() {
    }

    static String binaryName(ClassDesc descriptor) {
        String value = descriptor.descriptorString();
        if (!value.startsWith("L") || !value.endsWith(";")) {
            throw new IllegalArgumentException("Expected a class or interface descriptor, got " + value);
        }
        return value.substring(1, value.length() - 1).replace('/', '.');
    }
}
