package com.unecroe.ucjackpot.jackpot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedSelectorTest {
    @Test
    void selectsOnlyPositiveWeightChoices() {
        WeightedSelector<Integer> selector = new WeightedSelector<>();
        for (int i = 0; i < 50; i++) {
            assertEquals(2, selector.select(List.of(1, 2), value -> value == 2 ? 10.0 : 0.0));
        }
    }

    @Test
    void rejectsZeroTotalWeight() {
        WeightedSelector<Integer> selector = new WeightedSelector<>();
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> selector.select(List.of(1, 2), ignored -> 0.0));
        assertTrue(exception.getMessage().contains("positive"));
    }
}


