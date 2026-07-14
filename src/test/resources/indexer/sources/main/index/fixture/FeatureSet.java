package index.fixture;

import dependency.External;
import java.io.Serializable;
import java.util.*;

public sealed class FeatureSet<T extends Number & Comparable<T>>
        extends SourceBase
        implements Serializable
        permits Child {
    public static final int FIRST = 1, SECOND = 2;
    private List<? super T[]> values;
    External external;

    public FeatureSet() {
    }

    public <U extends CharSequence> List<? extends U> transform(U value, String... rest) {
        return List.of(value);
    }

    public void execute() {
        class Local {
            int ignored;
        }
        new Local();
    }

    static class Nested {
        int hidden;

        void hiddenMethod() {
        }
    }
}

final class Child extends FeatureSet<Integer> {
}

final class NestedChild extends FeatureSet.Nested {
}

interface Defaults {
    int CONSTANT = 1;

    default int value() {
        return CONSTANT;
    }
}

record Pair<T>(T left, T right) {
    Pair {
        Objects.requireNonNull(left);
    }
}

@interface Marker {
    String value() default "marker";
}

enum Shade {
    RED, BLUE;

    int code;
}
