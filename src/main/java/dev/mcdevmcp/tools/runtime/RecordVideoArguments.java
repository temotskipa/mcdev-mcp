package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record RecordVideoArguments(BigDecimal frames, RecordInterval interval, String output, BigDecimal gridCols, BigDecimal downscale, BigDecimal quality) {
    static RecordVideoArguments from(RecordVideoWireArguments wire) {
        String output = wire.output() == null ? null : RuntimeToolSupport.requiredString(wire.output(), "output");
        return new RecordVideoArguments(RuntimeToolSupport.requiredDecimal(wire.frames(), "frames"), MediaToolSupport.normalizeRecordInterval(wire.interval()), output, RuntimeToolSupport.optionalDecimal(wire.gridCols(), "gridCols"), RuntimeToolSupport.optionalDecimal(wire.downscale(), "downscale"), RuntimeToolSupport.optionalDecimal(wire.quality(), "quality"));
    }
}
