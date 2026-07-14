package dev.mcdevmcp.storage.model;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

final class PortablePathComponent {
    private static final Set<String> WINDOWS_DEVICE_NAMES = Set.of("CON", "PRN", "AUX", "NUL");

    private PortablePathComponent() {
    }

    static void requireValid(String value, String errorMessage) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.endsWith(".") || value.endsWith(" ") || value.chars().anyMatch(character -> Character.isISOControl(character) || isWindowsReservedCharacter(character)) || hasWindowsDeviceBasename(value)) {
            throw new IllegalArgumentException(errorMessage + value);
        }
        try {
            Path path = Path.of(value);
            if (path.getRoot() != null || path.isAbsolute() || path.getNameCount() != 1) {
                throw new IllegalArgumentException(errorMessage + value);
            }
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException(errorMessage + value, exception);
        }
    }

    private static boolean hasWindowsDeviceBasename(String value) {
        String basename = value.substring(0, value.indexOf('.') < 0 ? value.length() : value.indexOf('.')).toUpperCase(Locale.ROOT);
        return WINDOWS_DEVICE_NAMES.contains(basename) || basename.length() == 4 && (basename.startsWith("COM") || basename.startsWith("LPT")) && basename.charAt(3) >= '1' && basename.charAt(3) <= '9';
    }

    private static boolean isWindowsReservedCharacter(int character) {
        return switch (character) {
            case '<', '>', ':', '"', '/', '\\', '|', '?', '*' -> true;
            default -> false;
        };
    }
}
