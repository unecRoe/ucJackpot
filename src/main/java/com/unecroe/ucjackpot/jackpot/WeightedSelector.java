package com.unecroe.ucjackpot.jackpot;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToDoubleFunction;

public final class WeightedSelector<T> {
    public T select(List<T> values, ToDoubleFunction<T> weightFunction) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(weightFunction, "weightFunction");
        double total = values.stream().mapToDouble(weightFunction).filter(value -> value > 0).sum();
        if (total <= 0) {
            throw new IllegalArgumentException("Total weight must be positive");
        }
        double target = ThreadLocalRandom.current().nextDouble(total);
        double cursor = 0.0;
        for (T value : values) {
            double weight = Math.max(0.0, weightFunction.applyAsDouble(value));
            cursor += weight;
            if (target <= cursor) {
                return value;
            }
        }
        return values.getLast();
    }
}


