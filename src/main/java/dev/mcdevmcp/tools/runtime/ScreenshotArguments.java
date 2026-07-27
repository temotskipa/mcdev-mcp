package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record ScreenshotArguments(BigDecimal downscale, BigDecimal quality) {
    static ScreenshotArguments from(ScreenshotWireArguments wire) {
        return new ScreenshotArguments(RuntimeToolSupport.optionalDecimal(wire.downscale(), "downscale"), RuntimeToolSupport.optionalDecimal(wire.quality(), "quality"));
    }
}
