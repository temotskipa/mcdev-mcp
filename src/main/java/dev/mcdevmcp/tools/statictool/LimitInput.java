package dev.mcdevmcp.tools.statictool;

import java.math.BigDecimal;
import java.math.BigInteger;

record LimitInput(BigDecimal value) {
    static LimitInput fromWire(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            return new LimitInput(null);
        }
        BigDecimal decimal = switch (number) {
            case BigDecimal exact -> exact;
            case BigInteger integer -> new BigDecimal(integer);
            case Byte _, Short _, Integer _, Long _ -> BigDecimal.valueOf(number.longValue());
            default -> BigDecimal.valueOf(number.doubleValue());
        };
        return new LimitInput(decimal);
    }
}
