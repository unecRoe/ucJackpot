package com.unecroe.ucjackpot.item;

import com.unecroe.ucjackpot.config.JackpotDefinition;
import com.unecroe.ucjackpot.config.JackpotMode;
import com.unecroe.ucjackpot.config.PluginSettings;
import com.unecroe.ucjackpot.config.StorageSettings;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemValuatorTest {
    @Test
    void maxItemsPerEntryLimitsStacksNotStackAmount() {
        ItemStack stack = new ItemStack(Material.DIAMOND, 64);

        ItemValue value = new ItemValuator().evaluate(settings(), definition(), stack);

        assertTrue(value.accepted());
        assertEquals(64, value.normalized().getAmount());
        assertEquals(640.0, value.value());
    }

    private PluginSettings settings() {
        return new PluginSettings(
                "en",
                "en",
                1,
                true,
                true,
                List.of("jackpot", "jp"),
                true,
                true,
                true,
                10,
                "actionbar",
                false,
                true,
                "$",
                2,
                false,
                Set.of(),
                Set.of(),
                Set.of(),
                true,
                true,
                true,
                30,
                new StorageSettings("sqlite", "data.db", "localhost", 3306, "ucjackpot", "root", "", false, 5)
        );
    }

    private JackpotDefinition definition() {
        return new JackpotDefinition(
                "default",
                true,
                "Default",
                JackpotMode.HYBRID,
                true,
                1.0,
                1000.0,
                100.0,
                List.of(100.0),
                0.0,
                true,
                1.0,
                1,
                9,
                true,
                true,
                Map.of("DIAMOND", 10.0),
                60,
                2,
                1.0,
                25,
                0,
                true,
                10,
                false,
                24,
                1.0,
                false,
                0.0,
                0.0,
                "season-1",
                List.of(),
                true,
                false,
                0,
                0.0,
                List.of(),
                false,
                0.0,
                List.of(),
                true,
                "PAPER",
                "Jackpot Ticket",
                100.0,
                true,
                Set.of(),
                Set.of()
        );
    }
}
