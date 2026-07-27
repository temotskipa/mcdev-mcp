package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;
import java.util.Objects;

sealed interface RecordInterval {
    Object bridgeValue();

    double estimatedMillis();

    record Milliseconds(BigDecimal value) implements RecordInterval {
        public Milliseconds {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public Object bridgeValue() {
            return value;
        }

        @Override
        public double estimatedMillis() {
            double millis = value.doubleValue();
            return millis >= 1 ? millis : 17;
        }
    }

    record Text(String value) implements RecordInterval {
        public Text {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public Object bridgeValue() {
            return value;
        }

        @Override
        public double estimatedMillis() {
            return 17;
        }
    }
}
