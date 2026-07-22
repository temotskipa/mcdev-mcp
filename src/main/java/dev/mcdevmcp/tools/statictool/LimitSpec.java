package dev.mcdevmcp.tools.statictool;

import java.math.BigDecimal;
import java.math.BigInteger;

record LimitSpec(int defaultValue, int maximum) {
    LimitSpec {
        if (defaultValue < 1 || maximum < defaultValue) {
            throw new IllegalArgumentException("Invalid limit specification");
        }
    }

    NormalizedLimit normalize(Number requestedLimit) {
        return switch (requestedLimit) {
            case null -> new NormalizedLimit(defaultValue, false, true);
            case BigInteger value -> normalizeIntegral(value);
            case BigDecimal value -> normalizeDecimal(value);
            default -> normalizeFloating(requestedLimit.doubleValue());
        };
    }

    private NormalizedLimit normalizeDecimal(BigDecimal value) {
        if (value.signum() <= 0) return new NormalizedLimit(defaultValue, false, true);
        BigInteger floored = value.toBigInteger();
        return floored.signum() == 0 ? new NormalizedLimit(0, false, false) : normalizeIntegral(floored);
    }

    private NormalizedLimit normalizeFloating(double requested) {
        if (!Double.isFinite(requested) || requested <= 0) {
            return new NormalizedLimit(defaultValue, false, true);
        }
        if (requested >= Long.MAX_VALUE) return new NormalizedLimit(maximum, true, false);
        long floored = (long) Math.floor(requested);
        return floored == 0 ? new NormalizedLimit(0, false, false) : normalizeIntegral(BigInteger.valueOf(floored));
    }

    private NormalizedLimit normalizeIntegral(BigInteger value) {
        if (value.signum() <= 0) {
            return new NormalizedLimit(defaultValue, false, true);
        }
        BigInteger maximumValue = BigInteger.valueOf(maximum);
        if (value.compareTo(maximumValue) >= 0) {
            return new NormalizedLimit(maximum, value.compareTo(maximumValue) > 0, false);
        }
        return new NormalizedLimit(value.intValueExact(), false, false);
    }
}
