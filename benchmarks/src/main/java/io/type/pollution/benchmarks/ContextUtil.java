package io.type.pollution.benchmarks;

import java.util.Objects;

class ContextUtil {

    public static boolean isDuplicatedContext(Context context) {
        Context actual = Objects.requireNonNull(context);
        return ((InternalContext) actual).isDuplicated();
    }
}
